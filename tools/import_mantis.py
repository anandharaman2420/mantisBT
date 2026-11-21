#!/usr/bin/env python3
"""
One-time importer: live MantisBT 2.x database (MySQL/MariaDB or PostgreSQL)  ->  this app's PostgreSQL database.

* The SOURCE (your live Mantis DB) is only READ - nothing is ever written to it.
* Issue / note / project / user IDs are preserved, so issue numbers stay the same (0001234 -> 1234).
* Passwords are copied as they are (MD5); users log in with their old password and get upgraded to BCrypt.

Prerequisites:  pip install psycopg2-binary pymysql
Order:  1) start the Spring Boot backend once (it creates the empty tables)  2) STOP the backend
        3) run this script  4) start the backend again.

Example (MySQL source):
  python import_mantis.py --src-type mysql --src-host 10.0.0.5 --src-db bugtracker --src-user mantis \
         --src-password secret --dst-password mantis --wipe --dry-run
Run with --dry-run first (reads + validates + prints a report, writes nothing), then again without it.
"""
import argparse, datetime as dt, os, sys
from collections import Counter, defaultdict

UTC = dt.timezone.utc
EPOCH = dt.datetime(1970, 1, 1, tzinfo=UTC)

# ---------------------------------------------------------------- helpers
def ts(x, default=None):
    try:
        x = int(x or 0)
    except (TypeError, ValueError):
        return default
    return dt.datetime.fromtimestamp(x, tz=UTC) if x > 0 else default

def s(x, limit=None):
    if x is None: return ''
    if isinstance(x, (bytes, bytearray, memoryview)): x = bytes(x).decode('utf-8', 'replace')
    x = str(x).replace('\x00', '')
    return x[:limit] if limit else x

def b(x): return bool(int(x or 0))
def i(x, d=0):
    try: return int(x)
    except (TypeError, ValueError): return d

def parse():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--src-type', choices=['mysql', 'postgres'], required=True)
    p.add_argument('--src-host', default='localhost'); p.add_argument('--src-port', type=int)
    p.add_argument('--src-db', required=True); p.add_argument('--src-user', required=True)
    p.add_argument('--src-password', default='')
    p.add_argument('--src-prefix', default='mantis', help='Mantis table prefix (default: mantis -> mantis_bug_table)')
    p.add_argument('--dst-host', default='localhost'); p.add_argument('--dst-port', type=int, default=5432)
    p.add_argument('--dst-db', default='mantis'); p.add_argument('--dst-user', default='mantis')
    p.add_argument('--dst-password', default='mantis')
    p.add_argument('--files-dir', help='folder with attachments if Mantis stored them on disk (upload method DISK)')
    p.add_argument('--wipe', action='store_true', help='empty the target tables first (needed: the backend seeds an admin + sample project)')
    p.add_argument('--dry-run', action='store_true', help='read and validate only, write nothing')
    return p.parse_args()

# ---------------------------------------------------------------- main
def main():
    a = parse()
    import psycopg2, psycopg2.extras
    if a.src_type == 'mysql':
        import pymysql, pymysql.cursors
        src = pymysql.connect(host=a.src_host, port=a.src_port or 3306, user=a.src_user, password=a.src_password,
                              database=a.src_db, charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)
    else:
        src = psycopg2.connect(host=a.src_host, port=a.src_port or 5432, user=a.src_user, password=a.src_password,
                               dbname=a.src_db, cursor_factory=psycopg2.extras.RealDictCursor)
    sc = src.cursor()

    def T(name): return f'{a.src_prefix}_{name}_table'
    def rows(name, cols, where=''):
        sc.execute(f'SELECT {cols} FROM {T(name)} {where}')
        return list(sc.fetchall())

    warn = Counter(); notes = []
    def W(kind, msg=None):
        warn[kind] += 1
        if msg and warn[kind] <= 5: notes.append(f'  - {kind}: {msg}')

    print('Reading source ...')
    # ---- users
    users = rows('user', 'id,username,realname,email,password,enabled,protected,access_level,login_count,last_visit,date_created')
    user_ids = {u['id'] for u in users}
    admins = [u['id'] for u in users if i(u['access_level']) >= 90]
    admin_id = admins[0] if admins else (users[0]['id'] if users else None)
    if admin_id is None: sys.exit('Source has no users - is the prefix right? (--src-prefix)')
    uid = lambda x: x if x in user_ids else None

    # ---- projects
    projects = rows('project', 'id,name,status,enabled,view_state,description')
    project_ids = {p['id'] for p in projects}
    pu = [r for r in rows('project_user_list', 'project_id,user_id,access_level')
          if r['project_id'] in project_ids and r['user_id'] in user_ids]

    # ---- categories (Mantis has global categories with project_id=0 -> copy them into each project that uses them)
    src_cats = {c['id']: c for c in rows('category', 'id,project_id,user_id,name')}
    cat_rows = {}                                  # new_id -> (project_id, name, handler)
    cat_by_name = {}                               # (project_id, name) -> new_id
    for c in src_cats.values():
        if c['project_id'] in project_ids:
            cat_rows[c['id']] = (c['project_id'], s(c['name'], 128), uid(c['user_id']))
            cat_by_name[(c['project_id'], s(c['name'], 128))] = c['id']
    next_cat = max(list(src_cats) + [0]) + 1
    def cat_for(project_id, src_cat_id):
        nonlocal next_cat
        c = src_cats.get(src_cat_id)
        if not c: return None
        if c['project_id'] == project_id: return src_cat_id
        key = (project_id, s(c['name'], 128))
        if key not in cat_by_name:
            cat_rows[next_cat] = (project_id, key[1], uid(c['user_id']))
            cat_by_name[key] = next_cat; next_cat += 1
        return cat_by_name[key]

    versions = [r for r in rows('project_version', 'id,project_id,version,description,released,obsolete,date_order')
                if r['project_id'] in project_ids]
    tags = rows('tag', 'id,user_id,name,description,date_created')

    # ---- issues (+ merged text)
    texts = {t['id']: t for t in rows('bug_text', 'id,description,steps_to_reproduce,additional_information')}
    bugs = rows('bug', 'id,project_id,reporter_id,handler_id,duplicate_id,priority,severity,reproducibility,status,'
                       'resolution,projection,eta,bug_text_id,os,os_build,platform,version,fixed_in_version,'
                       'target_version,build,view_state,summary,sticky,category_id,date_submitted,last_updated')
    issues = []; bug_ids = set(); bug_status = Counter()
    for r in bugs:
        if r['project_id'] not in project_ids:
            W('issue skipped (unknown project)', f"bug {r['id']}"); continue
        rep = r['reporter_id'] if r['reporter_id'] in user_ids else admin_id
        if rep != r['reporter_id']: W('reporter missing -> admin', f"bug {r['id']}")
        t = texts.get(r['bug_text_id'], {})
        bug_ids.add(r['id']); bug_status[i(r['status'])] += 1
        issues.append((r['id'], r['project_id'], rep, uid(r['handler_id']) if i(r['handler_id']) else None,
            r['duplicate_id'] if i(r['duplicate_id']) else None, i(r['priority'], 30), i(r['severity'], 50),
            i(r['reproducibility'], 10), i(r['status'], 10), i(r['resolution'], 10), i(r['projection'], 10), i(r['eta'], 10),
            cat_for(r['project_id'], r['category_id']), ts(r['date_submitted'], EPOCH), ts(r['last_updated'], EPOCH),
            s(r['os'], 32), s(r['os_build'], 32), s(r['platform'], 32), s(r['version'], 64), s(r['fixed_in_version'], 64),
            s(r['target_version'], 64), s(r['build'], 32), i(r['view_state'], 10), s(r['summary'], 128) or '(no summary)',
            b(r['sticky']), s(t.get('description')), s(t.get('steps_to_reproduce')), s(t.get('additional_information'))))
    issues = [x for x in issues]
    dup_fix = [x for x in issues if x[4] and x[4] not in bug_ids]    # duplicate_id is not a FK, only a number: keep it

    bug_tags = {(r['bug_id'], r['tag_id']) for r in rows('bug_tag', 'bug_id,tag_id')}
    tag_ids = {t['id'] for t in tags}
    bug_tags = [x for x in bug_tags if x[0] in bug_ids and x[1] in tag_ids]

    # ---- notes
    ntext = {t['id']: t['note'] for t in rows('bugnote_text', 'id,note')}
    notes_out = []
    for r in rows('bugnote', 'id,bug_id,reporter_id,bugnote_text_id,view_state,date_submitted,last_modified,note_type'):
        if r['bug_id'] not in bug_ids: W('note skipped (unknown issue)'); continue
        rep = r['reporter_id'] if r['reporter_id'] in user_ids else admin_id
        notes_out.append((r['id'], r['bug_id'], rep, s(ntext.get(r['bugnote_text_id'])), i(r['view_state'], 10),
                          ts(r['date_submitted'], EPOCH), ts(r['last_modified'], EPOCH), i(r['note_type'])))

    monitors = {(r['user_id'], r['bug_id']) for r in rows('bug_monitor', 'user_id,bug_id')}
    monitors = [m for m in monitors if m[0] in user_ids and m[1] in bug_ids]

    # ---- relationships: this app stores only 0 duplicate-of, 1 related, 2 parent-of  (3/4 are the flipped forms)
    rels = []
    for r in rows('bug_relationship', 'id,source_bug_id,destination_bug_id,relationship_type'):
        a_, b_, t = r['source_bug_id'], r['destination_bug_id'], i(r['relationship_type'], 1)
        if a_ not in bug_ids or b_ not in bug_ids or a_ == b_: W('relationship skipped'); continue
        if t == 3: a_, b_, t = b_, a_, 2
        elif t == 4: a_, b_, t = b_, a_, 0
        rels.append((r['id'], a_, b_, t))

    hist = []
    for r in rows('bug_history', 'id,user_id,bug_id,field_name,old_value,new_value,type,date_modified'):
        if r['bug_id'] not in bug_ids: continue
        hist.append((r['id'], r['bug_id'], uid(r['user_id']), s(r['field_name'], 64), s(r['old_value']) or None,
                     s(r['new_value']) or None, i(r['type']), ts(r['date_modified'], EPOCH)))

    news = [(r['id'], r['project_id'] if r['project_id'] in project_ids else None,
             r['poster_id'] if r['poster_id'] in user_ids else admin_id, ts(r['date_posted'], EPOCH),
             ts(r['last_modified'], EPOCH), i(r['view_state'], 10), b(r['announcement']), s(r['headline'], 64), s(r['body']))
            for r in rows('news', 'id,project_id,poster_id,view_state,announcement,headline,body,last_modified,date_posted')]

    file_meta = [r for r in rows('bug_file', 'id,bug_id,user_id,title,description,diskfile,filename,folder,filesize,file_type,date_added')
                 if r['bug_id'] in bug_ids]

    # ---------------------------------------------------------------- report
    print('\n=== SOURCE REPORT ===')
    for k, v in [('users', len(users)), ('projects', len(projects)), ('project members', len(pu)),
                 ('categories (after copying global ones)', len(cat_rows)), ('versions', len(versions)), ('tags', len(tags)),
                 ('issues', len(issues)), ('issue-tag links', len(bug_tags)), ('notes', len(notes_out)),
                 ('monitors', len(monitors)), ('relationships', len(rels)), ('history rows', len(hist)),
                 ('attachments', len(file_meta)), ('news', len(news))]:
        print(f'  {k:42s} {v}')
    print('  issues by status:', dict(sorted(bug_status.items())))
    if warn:
        print('  warnings:'); [print(f'    {k}: {v}') for k, v in warn.items()]; print('\n'.join(notes))
    if a.dry_run:
        print('\nDry run finished - nothing was written.'); return

    # ---------------------------------------------------------------- write
    dst = psycopg2.connect(host=a.dst_host, port=a.dst_port, dbname=a.dst_db, user=a.dst_user, password=a.dst_password)
    dc = dst.cursor()
    tables = ['bug_tag', 'bug_file', 'bug_history', 'bug_monitor', 'bug_relationship', 'bugnote', 'news', 'bug',
              'tag', 'project_version', 'category', 'project_user_list', 'project', 'user']
    full = lambda t: f'mantis_{t}_table'
    try:
        dc.execute(f'SELECT count(*) FROM {full("user")}')
    except psycopg2.Error:
        sys.exit('Target tables do not exist yet - start the Spring Boot backend once, then stop it and re-run.')
    if dc.fetchone()[0] > 0 and not a.wipe:
        sys.exit('Target already contains data. Re-run with --wipe to replace it.')
    dst.rollback()
    if a.wipe:
        dc.execute('TRUNCATE ' + ', '.join(full(t) for t in tables) + ' RESTART IDENTITY CASCADE')

    def ins(table, cols, data):
        if data:
            psycopg2.extras.execute_values(dc, f'INSERT INTO {full(table)} ({cols}) VALUES %s', data, page_size=500)
        print(f'  wrote {len(data):7d}  {table}')

    print('\nWriting target ...')
    ins('user', 'id,username,realname,email,password,enabled,protected,access_level,login_count,last_visit,date_created',
        [(u['id'], s(u['username'], 191), s(u['realname'], 191), s(u['email'], 191), s(u['password'], 255), b(u['enabled']),
          b(u['protected']), i(u['access_level'], 25), i(u['login_count']), ts(u['last_visit']), ts(u['date_created'], EPOCH)) for u in users])
    ins('project', 'id,name,status,enabled,view_state,description',
        [(p['id'], s(p['name'], 128), i(p['status'], 10), b(p['enabled']), i(p['view_state'], 10), s(p['description'])) for p in projects])
    ins('project_user_list', 'project_id,user_id,access_level', [(r['project_id'], r['user_id'], i(r['access_level'], 25)) for r in pu])
    ins('category', 'id,project_id,name,user_id', [(k, v[0], v[1], v[2]) for k, v in cat_rows.items()])
    ins('project_version', 'id,project_id,version,description,released,obsolete,date_order',
        [(v['id'], v['project_id'], s(v['version'], 64), s(v['description']), b(v['released']), b(v['obsolete']),
          ts(v['date_order'], EPOCH)) for v in versions])
    seen = set(); trows = []
    for t in tags:
        n = s(t['name'], 100)
        if n.lower() in seen: W('duplicate tag name skipped', n); continue
        seen.add(n.lower()); trows.append((t['id'], n, s(t['description']), uid(t['user_id']), ts(t['date_created'], EPOCH)))
    ins('tag', 'id,name,description,user_id,date_created', trows)
    ins('bug', 'id,project_id,reporter_id,handler_id,duplicate_id,priority,severity,reproducibility,status,resolution,projection,eta,'
               'category_id,date_submitted,last_updated,os,os_build,platform,version,fixed_in_version,target_version,build,view_state,'
               'summary,sticky,description,steps_to_reproduce,additional_information', issues)
    kept_tags = {t[0] for t in trows}
    ins('bug_tag', 'bug_id,tag_id', [x for x in bug_tags if x[1] in kept_tags])
    ins('bugnote', 'id,bug_id,reporter_id,text,view_state,date_submitted,last_modified,note_type', notes_out)
    ins('bug_monitor', 'user_id,bug_id', monitors)
    ins('bug_relationship', 'id,source_bug_id,destination_bug_id,relationship_type', rels)
    ins('bug_history', 'id,bug_id,user_id,field_name,old_value,new_value,type,date_modified', hist)
    ins('news', 'id,project_id,poster_id,date_posted,last_modified,view_state,announcement,headline,body', news)

    # attachments: fetched one by one (can be large)
    nfiles = 0; missing = 0
    for m in file_meta:
        sc.execute(f'SELECT content FROM {T("bug_file")} WHERE id = %s', (m['id'],))
        content = sc.fetchone()['content']
        content = bytes(content) if content else b''
        if not content and a.files_dir:
            for base in (a.files_dir, m.get('folder') or ''):
                path = os.path.join(base, s(m['diskfile']))
                if base and os.path.isfile(path):
                    content = open(path, 'rb').read(); break
        if not content:
            missing += 1; W('attachment without content (skipped)', s(m['filename'])); continue
        dc.execute(f'INSERT INTO {full("bug_file")} (id,bug_id,user_id,title,description,filename,filesize,file_type,date_added,content) '
                   'VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',
                   (m['id'], m['bug_id'], uid(m['user_id']), s(m['title'], 250), s(m['description']), s(m['filename'], 250)[:250],
                    len(content), s(m['file_type'], 250), ts(m['date_added'], EPOCH), psycopg2.Binary(content)))
        nfiles += 1
    print(f'  wrote {nfiles:7d}  bug_file   ({missing} skipped - no content; use --files-dir if Mantis stored files on disk)')

    # advance the id sequences so new records do not collide with imported ids
    for t in tables:
        if t in ('bug_tag',): continue
        dc.execute(f"SELECT setval(pg_get_serial_sequence('{full(t)}','id'), GREATEST((SELECT COALESCE(MAX(id),0) FROM {full(t)}),1))")
    dst.commit()

    # ---------------------------------------------------------------- verify
    print('\n=== VERIFICATION (target) ===')
    dc.execute(f'SELECT status, count(*) FROM {full("bug")} GROUP BY status ORDER BY status')
    got = dict(dc.fetchall())
    print('  issues by status:', got)
    print('  matches source  :', 'YES' if got == dict(bug_status) else 'NO - compare with the source report above')
    print('\nDone. Start the backend and log in with an existing Mantis username + its old password.')

if __name__ == '__main__':
    main()
