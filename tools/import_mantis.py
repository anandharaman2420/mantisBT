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
    p.add_argument('--src-charset', default='utf8mb4', help='MySQL connection charset (use latin1 if umlauts come out garbled)')
    p.add_argument('--no-verify', action='store_true', help='skip the reconciliation after the import')
    p.add_argument('--verify-only', action='store_true', help='do not import, only compare source and target (reconciliation)')
    return p.parse_args()

# ---------------------------------------------------------------- main
def connect_source(a):
    """connection to the live MantisBT database (read only use)"""
    if a.src_type == 'mysql':
        import pymysql, pymysql.cursors
        return pymysql.connect(host=a.src_host, port=a.src_port or 3306, user=a.src_user, password=a.src_password,
                               database=a.src_db, charset=a.src_charset, cursorclass=pymysql.cursors.DictCursor)
    import psycopg2, psycopg2.extras
    return psycopg2.connect(host=a.src_host, port=a.src_port or 5432, user=a.src_user, password=a.src_password,
                            dbname=a.src_db, cursor_factory=psycopg2.extras.RealDictCursor)


def stream(conn, sql, kind, name):
    """yields rows one by one WITHOUT loading the whole result set into memory (server side cursor)"""
    if kind == 'mysql':
        import pymysql.cursors
        cur = conn.cursor(pymysql.cursors.SSDictCursor)
    else:
        import psycopg2.extras
        cur = conn.cursor(name=name, cursor_factory=psycopg2.extras.RealDictCursor)
        cur.itersize = 2000
    cur.execute(sql)
    for row in cur:
        yield row
    cur.close()


def scalar_count(cur, sql):
    cur.execute(sql); r = cur.fetchone()
    return int((list(r.values())[0] if isinstance(r, dict) else r[0]) or 0)


def reconcile(a, written=None, attachments_missing=0):
    """
    Compares the target with the live source - independently of the transformation code, with SQL on both sides:
    row counts, issues per project / status, notes, time tracking, history, and a SHA-256 digest over every issue
    (ids, classification, dates, texts) and every note. Returns the number of failed checks.
    """
    import hashlib, psycopg2
    src = connect_source(a)
    sc = src.cursor()
    dst = psycopg2.connect(host=a.dst_host, port=a.dst_port, dbname=a.dst_db, user=a.dst_user, password=a.dst_password)
    dc = dst.cursor()
    T = lambda n: f'{a.src_prefix}_{n}_table'
    D = lambda n: f'mantis_{n}_table'
    known = f"{T('bug')} b JOIN {T('project')} p ON p.id = b.project_id"      # issues of projects that exist
    fails = 0

    def one(cur, sql):
        cur.execute(sql); r = cur.fetchone()
        return int((list(r.values())[0] if isinstance(r, dict) else r[0]) or 0)

    def pairs(cur, sql):
        cur.execute(sql)
        return {(r['k'] if isinstance(r, dict) else r[0]): int(r['n'] if isinstance(r, dict) else r[1]) for r in cur.fetchall()}

    def check(name, s_val, t_val, strict=True):
        nonlocal fails
        ok = s_val == t_val
        if not ok and strict: fails += 1
        tag = 'PASS' if ok else ('FAIL' if strict else 'INFO')
        shown = f'{s_val} / {t_val}' if not isinstance(s_val, dict) else f'{len(s_val)} groups'
        print(f'  [{tag}] {name:48s} source / target: {shown}')
        if not ok and isinstance(s_val, dict):
            diff = {k: (s_val.get(k), t_val.get(k)) for k in set(s_val) | set(t_val) if s_val.get(k) != t_val.get(k)}
            print(f'         differences (key: source, target): {dict(list(diff.items())[:10])}')

    print('\n=== RECONCILIATION (source vs target) ===')
    check('users', one(sc, f"SELECT COUNT(*) AS n FROM {T('user')}"), one(dc, f"SELECT COUNT(*) FROM {D('user')}"))
    check('projects', one(sc, f"SELECT COUNT(*) AS n FROM {T('project')}"), one(dc, f"SELECT COUNT(*) FROM {D('project')}"))
    check('issues', one(sc, f"SELECT COUNT(*) AS n FROM {known}"), one(dc, f"SELECT COUNT(*) FROM {D('bug')}"))
    check('issues per project', pairs(sc, f"SELECT b.project_id AS k, COUNT(*) AS n FROM {known} GROUP BY b.project_id"),
          pairs(dc, f"SELECT project_id, COUNT(*) FROM {D('bug')} GROUP BY project_id"))
    check('issues per status', pairs(sc, f"SELECT b.status AS k, COUNT(*) AS n FROM {known} GROUP BY b.status"),
          pairs(dc, f"SELECT status, COUNT(*) FROM {D('bug')} GROUP BY status"))
    check('issues per priority', pairs(sc, f"SELECT b.priority AS k, COUNT(*) AS n FROM {known} GROUP BY b.priority"),
          pairs(dc, f"SELECT priority, COUNT(*) FROM {D('bug')} GROUP BY priority"))
    notes_src = f"{T('bugnote')} n JOIN {known.replace(' b JOIN', ' b JOIN', 1)} ON b.id = n.bug_id"
    check('notes', one(sc, f"SELECT COUNT(*) AS n FROM {notes_src}"), one(dc, f"SELECT COUNT(*) FROM {D('bugnote')}"))
    check('private notes', one(sc, f"SELECT COUNT(*) AS n FROM {notes_src} WHERE n.view_state = 50"),
          one(dc, f"SELECT COUNT(*) FROM {D('bugnote')} WHERE view_state = 50"))
    check('time tracking (minutes)', one(sc, f"SELECT COALESCE(SUM(n.time_tracking),0) AS n FROM {notes_src}"),
          one(dc, f"SELECT COALESCE(SUM(time_tracking),0) FROM {D('bugnote')}"))
    check('history rows', one(sc, f"SELECT COUNT(*) AS n FROM {T('bug_history')} h JOIN {known} ON b.id = h.bug_id"),
          one(dc, f"SELECT COUNT(*) FROM {D('bug_history')}"))
    check('news', one(sc, f"SELECT COUNT(*) AS n FROM {T('news')}"), one(dc, f"SELECT COUNT(*) FROM {D('news')}"))
    # legitimate differences are possible below (duplicates / orphans are dropped on purpose): informational only
    check('tags (distinct names)', one(sc, f"SELECT COUNT(DISTINCT LOWER(name)) AS n FROM {T('tag')}"), one(dc, f"SELECT COUNT(*) FROM {D('tag')}"), False)
    check('custom fields (distinct names)', one(sc, f"SELECT COUNT(DISTINCT LOWER(name)) AS n FROM {T('custom_field')}"),
          one(dc, f"SELECT COUNT(*) FROM {D('custom_field')}"), False)
    check('relationships', one(sc, f"SELECT COUNT(*) AS n FROM {T('bug_relationship')}"), one(dc, f"SELECT COUNT(*) FROM {D('bug_relationship')}"), False)
    att_src = one(sc, f"SELECT COUNT(*) AS n FROM {T('bug_file')} f JOIN {known} ON b.id = f.bug_id")
    check('attachments' + (' (minus those without content)' if written else ''),
          att_src - attachments_missing if written else att_src, one(dc, f"SELECT COUNT(*) FROM {D('bug_file')}"), strict=bool(written))

    if written:                                    # everything the importer wrote is really in the target
        for t, n in written.items():
            check(f'rows written to {t}', n, one(dc, f"SELECT COUNT(*) FROM {D(t)}"))

    def digest(rows):
        h = hashlib.sha256(); n = 0
        for line in rows:
            h.update(line.encode('utf-8', 'replace')); h.update(b'\n'); n += 1
        return f'{n}:{h.hexdigest()[:16]}'

    def src_issue_lines():
        sql = (f"SELECT b.id, b.project_id, b.status, b.resolution, b.priority, b.severity, b.reproducibility, b.view_state, b.sticky, "
               f"b.date_submitted, b.last_updated, b.summary, t.description, t.steps_to_reproduce, t.additional_information "
               f"FROM {known} LEFT JOIN {T('bug_text')} t ON t.id = b.bug_text_id ORDER BY b.id")
        for r in stream(src, sql, a.src_type, 'src_issues'):
            yield '|'.join(map(str, [r['id'], r['project_id'], i(r['status']), i(r['resolution']), i(r['priority']), i(r['severity']),
                                     i(r['reproducibility']), i(r['view_state']), 1 if b(r['sticky']) else 0, i(r['date_submitted']),
                                     i(r['last_updated']), s(r['summary'], 128) or '(no summary)', s(r['description']),
                                     s(r['steps_to_reproduce']), s(r['additional_information'])]))

    def dst_issue_lines():
        sql = ("SELECT id, project_id, status, resolution, priority, severity, reproducibility, view_state, sticky, "
               "EXTRACT(EPOCH FROM date_submitted)::bigint, EXTRACT(EPOCH FROM last_updated)::bigint, summary, description, "
               f"steps_to_reproduce, additional_information FROM {D('bug')} ORDER BY id")
        cur = dst.cursor(name='dst_issues'); cur.itersize = 2000; cur.execute(sql)
        for r in cur:
            yield '|'.join(map(str, [r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], 1 if r[8] else 0, r[9], r[10], r[11], r[12], r[13], r[14]]))
        cur.close()

    def src_note_lines():
        sql = (f"SELECT n.id, n.bug_id, n.view_state, n.date_submitted, n.time_tracking, x.note FROM {notes_src} "
               f"LEFT JOIN {T('bugnote_text')} x ON x.id = n.bugnote_text_id ORDER BY n.id")
        for r in stream(src, sql, a.src_type, 'src_notes'):
            yield '|'.join(map(str, [r['id'], r['bug_id'], i(r['view_state']), i(r['date_submitted']), i(r['time_tracking']), s(r['note'])]))

    def dst_note_lines():
        cur = dst.cursor(name='dst_notes'); cur.itersize = 2000
        cur.execute(f"SELECT id, bug_id, view_state, EXTRACT(EPOCH FROM date_submitted)::bigint, time_tracking, text FROM {D('bugnote')} ORDER BY id")
        for r in cur:
            yield '|'.join(map(str, list(r)))
        cur.close()

    check('content digest of every issue (texts, dates, status ...)', digest(src_issue_lines()), digest(dst_issue_lines()))
    check('content digest of every note', digest(src_note_lines()), digest(dst_note_lines()))
    print('\nRECONCILIATION: ' + ('PASS - the target matches the source.' if not fails else f'FAIL - {fails} check(s) differ, see above.'))
    src.rollback(); dst.rollback()
    return fails


def main():
    a = parse()
    import psycopg2, psycopg2.extras
    if a.verify_only:
        sys.exit(2 if reconcile(a) else 0)
    src = connect_source(a)
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
    # ---- sub-projects (this app supports ONE parent per project; Mantis allowed several -> first one is kept)
    parent_of = {}
    try:
        hier = rows('project_hierarchy', 'child_id,parent_id,inherit_parent')
    except Exception:
        if hasattr(src, 'rollback'): src.rollback()
        hier = []
    for r in hier:
        c, par = r['child_id'], r['parent_id']
        if c not in project_ids or par not in project_ids or c == par: continue
        if c in parent_of: W('project with several parents (first kept)', f'project {c}'); continue
        parent_of[c] = (par, b(r['inherit_parent']))
    def _cycle(c):
        seen, cur = {c}, c
        while cur in parent_of:
            cur = parent_of[cur][0]
            if cur in seen: return True
            seen.add(cur)
        return False
    for c in list(parent_of):
        if c in parent_of and _cycle(c):
            W('project hierarchy cycle removed', f'project {c}'); del parent_of[c]
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

    # ---- issues (+ merged text). Two passes so that even huge databases need little memory:
    #   pass 1 (light): which issues are importable, status counts, global categories that must be copied into projects
    #   pass 2 (while writing): the full rows incl. text are streamed straight into the target
    def row_stream(sql, name):
        conn = connect_source(a)
        try:
            yield from stream(conn, sql, a.src_type, name)
        finally:
            conn.close()

    bug_ids = set(); bug_status = Counter(); n_issues = 0
    for r in row_stream(f"SELECT id, project_id, reporter_id, status, category_id FROM {T('bug')}", 'issues_pass1'):
        if r['project_id'] not in project_ids:
            W('issue skipped (unknown project)', f"bug {r['id']}"); continue
        if r['reporter_id'] not in user_ids: W('reporter missing -> admin', f"bug {r['id']}")
        bug_ids.add(r['id']); bug_status[i(r['status'])] += 1; n_issues += 1
        cat_for(r['project_id'], r['category_id'])                     # registers the project's copy of a global category

    def issue_rows():
        sql = (f"SELECT b.id, b.project_id, b.reporter_id, b.handler_id, b.duplicate_id, b.priority, b.severity, b.reproducibility, "
               f"b.status, b.resolution, b.projection, b.eta, b.os, b.os_build, b.platform, b.version, b.fixed_in_version, "
               f"b.target_version, b.build, b.view_state, b.summary, b.sticky, b.category_id, b.date_submitted, b.last_updated, "
               f"t.description, t.steps_to_reproduce, t.additional_information "
               f"FROM {T('bug')} b LEFT JOIN {T('bug_text')} t ON t.id = b.bug_text_id ORDER BY b.id")
        for r in row_stream(sql, 'issues_pass2'):
            if r['project_id'] not in project_ids: continue
            rep = r['reporter_id'] if r['reporter_id'] in user_ids else admin_id
            yield (r['id'], r['project_id'], rep, uid(r['handler_id']) if i(r['handler_id']) else None,
                r['duplicate_id'] if i(r['duplicate_id']) else None, i(r['priority'], 30), i(r['severity'], 50),
                i(r['reproducibility'], 10), i(r['status'], 10), i(r['resolution'], 10), i(r['projection'], 10), i(r['eta'], 10),
                cat_for(r['project_id'], r['category_id']), ts(r['date_submitted'], EPOCH), ts(r['last_updated'], EPOCH),
                s(r['os'], 32), s(r['os_build'], 32), s(r['platform'], 32), s(r['version'], 64), s(r['fixed_in_version'], 64),
                s(r['target_version'], 64), s(r['build'], 32), i(r['view_state'], 10), s(r['summary'], 128) or '(no summary)',
                b(r['sticky']), s(r['description']), s(r['steps_to_reproduce']), s(r['additional_information']))

    bug_tags = {(r['bug_id'], r['tag_id']) for r in rows('bug_tag', 'bug_id,tag_id')}
    tag_ids = {t['id'] for t in tags}
    bug_tags = [x for x in bug_tags if x[0] in bug_ids and x[1] in tag_ids]

    # ---- notes (text joined in SQL, streamed while writing)
    known_bugs = f"{T('bug')} b JOIN {T('project')} p ON p.id = b.project_id"
    n_notes = scalar_count(sc, f"SELECT COUNT(*) AS n FROM {T('bugnote')} n JOIN {known_bugs} ON b.id = n.bug_id")
    skipped_notes = scalar_count(sc, f"SELECT COUNT(*) AS n FROM {T('bugnote')}") - n_notes
    if skipped_notes: warn['note skipped (unknown issue)'] += skipped_notes

    def note_rows():
        sql = (f"SELECT n.id, n.bug_id, n.reporter_id, n.view_state, n.date_submitted, n.last_modified, n.note_type, n.time_tracking, x.note "
               f"FROM {T('bugnote')} n LEFT JOIN {T('bugnote_text')} x ON x.id = n.bugnote_text_id ORDER BY n.id")
        for r in row_stream(sql, 'notes'):
            if r['bug_id'] not in bug_ids: continue
            rep = r['reporter_id'] if r['reporter_id'] in user_ids else admin_id
            yield (r['id'], r['bug_id'], rep, s(r['note']), i(r['view_state'], 10), ts(r['date_submitted'], EPOCH),
                   ts(r['last_modified'], EPOCH), i(r['note_type']), max(i(r['time_tracking']), 0))

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

    n_hist = scalar_count(sc, f"SELECT COUNT(*) AS n FROM {T('bug_history')} h JOIN {known_bugs} ON b.id = h.bug_id")

    def hist_rows():
        sql = (f"SELECT id, user_id, bug_id, field_name, old_value, new_value, type, date_modified FROM {T('bug_history')} ORDER BY id")
        for r in row_stream(sql, 'history'):
            if r['bug_id'] not in bug_ids: continue
            yield (r['id'], r['bug_id'], uid(r['user_id']), s(r['field_name'], 64), s(r['old_value']) or None,
                   s(r['new_value']) or None, i(r['type']), ts(r['date_modified'], EPOCH))

    # ---- custom fields (definitions, project links, values)
    cfields = rows('custom_field', 'id,name,type,possible_values,default_value,valid_regexp,access_level_r,access_level_rw,'
                                    'length_min,length_max,require_report,require_update,display_report,display_update,'
                                    'display_resolved,display_closed,filter_by')
    cf_ids = {c['id'] for c in cfields}
    cf_type = {c['id']: i(c['type']) for c in cfields}
    cf_links = [r for r in rows('custom_field_project', 'field_id,project_id,sequence')
                if r['field_id'] in cf_ids and r['project_id'] in project_ids]
    try:
        cf_raw = rows('custom_field_string', 'field_id,bug_id,value,text')
    except Exception:                       # older schema without the "text" column
        if hasattr(src, 'rollback'): src.rollback()
        cf_raw = rows('custom_field_string', 'field_id,bug_id,value')
    cf_values = []
    for r in cf_raw:
        if r['field_id'] not in cf_ids or r['bug_id'] not in bug_ids: continue
        v = s(r.get('text')) or s(r['value'])
        if cf_type.get(r['field_id']) in (5, 7): v = v.strip('|')        # checkbox / multi-select: "|a|b|" -> "a|b"
        if v != '': cf_values.append((r['field_id'], r['bug_id'], v))

    news = [(r['id'], r['project_id'] if r['project_id'] in project_ids else None,
             r['poster_id'] if r['poster_id'] in user_ids else admin_id, ts(r['date_posted'], EPOCH),
             ts(r['last_modified'], EPOCH), i(r['view_state'], 10), b(r['announcement']), s(r['headline'], 64), s(r['body']))
            for r in rows('news', 'id,project_id,poster_id,view_state,announcement,headline,body,last_modified,date_posted')]

    file_meta = [r for r in rows('bug_file', 'id,bug_id,user_id,title,description,diskfile,filename,folder,filesize,file_type,date_added')
                 if r['bug_id'] in bug_ids]

    # ---------------------------------------------------------------- report
    print('\n=== SOURCE REPORT ===')
    for k, v in [('users', len(users)), ('projects', len(projects)), ('sub-projects', len(parent_of)), ('project members', len(pu)),
                 ('categories (after copying global ones)', len(cat_rows)), ('versions', len(versions)), ('tags', len(tags)),
                 ('issues', n_issues), ('issue-tag links', len(bug_tags)), ('notes', n_notes),
                 ('monitors', len(monitors)), ('relationships', len(rels)), ('history rows', n_hist),
                 ('attachments', len(file_meta)), ('news', len(news)), ('custom fields', len(cfields)),
                 ('custom field links', len(cf_links)), ('custom field values', len(cf_values))]:
        print(f'  {k:42s} {v}')
    print('  issues by status:', dict(sorted(bug_status.items())))
    if warn:
        print('  warnings:'); [print(f'    {k}: {v}') for k, v in warn.items()]; print('\n'.join(notes))
    if a.dry_run:
        print('\nDry run finished - nothing was written.'); return

    # ---------------------------------------------------------------- write
    dst = psycopg2.connect(host=a.dst_host, port=a.dst_port, dbname=a.dst_db, user=a.dst_user, password=a.dst_password)
    dc = dst.cursor()
    tables = ['custom_field_string', 'custom_field_project', 'custom_field', 'bug_tag', 'bug_file', 'bug_history', 'bug_monitor', 'bug_relationship', 'bugnote', 'news', 'bug',
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

    written = {}

    def ins(table, cols, data):
        if data:
            psycopg2.extras.execute_values(dc, f'INSERT INTO {full(table)} ({cols}) VALUES %s', data, page_size=500)
        written[table] = len(data)
        print(f'  wrote {len(data):7d}  {table}')

    def ins_stream(table, cols, rows_iter, chunk=2000):
        n = 0; batch = []
        for row in rows_iter:
            batch.append(row)
            if len(batch) >= chunk:
                psycopg2.extras.execute_values(dc, f'INSERT INTO {full(table)} ({cols}) VALUES %s', batch, page_size=chunk); n += len(batch); batch = []
        if batch:
            psycopg2.extras.execute_values(dc, f'INSERT INTO {full(table)} ({cols}) VALUES %s', batch, page_size=chunk); n += len(batch)
        written[table] = n
        print(f'  wrote {n:7d}  {table}')

    print('\nWriting target ...')
    ins('user', 'id,username,realname,email,password,enabled,protected,access_level,login_count,last_visit,date_created',
        [(u['id'], s(u['username'], 191), s(u['realname'], 191), s(u['email'], 191), s(u['password'], 255), b(u['enabled']),
          b(u['protected']), i(u['access_level'], 25), i(u['login_count']), ts(u['last_visit']), ts(u['date_created'], EPOCH)) for u in users])
    ins('project', 'id,name,status,enabled,view_state,description',
        [(p['id'], s(p['name'], 128), i(p['status'], 10), b(p['enabled']), i(p['view_state'], 10), s(p['description'])) for p in projects])
    for child, (par, inh) in parent_of.items():
        dc.execute(f'UPDATE {full("project")} SET parent_id = %s, inherit_parent = %s WHERE id = %s', (par, inh, child))
    print(f'  linked {len(parent_of):7d}  sub-projects')
    seen_cf = set(); cf_rows = []
    for c in cfields:
        n = s(c['name'], 64)
        if n.lower() in seen_cf: W('duplicate custom field name skipped', n); continue
        seen_cf.add(n.lower())
        cf_rows.append((c['id'], n, i(c['type']), s(c['possible_values']), s(c['default_value']), s(c['valid_regexp'], 255),
                        i(c['access_level_r'], 10), max(i(c['access_level_rw'], 25), i(c['access_level_r'], 10)),
                        i(c['length_min']), i(c['length_max']), b(c['require_report']), b(c['require_update']),
                        b(c['display_report']), b(c['display_update']), b(c['display_resolved']), b(c['display_closed']),
                        b(c['filter_by'])))
    kept_cf = {r[0] for r in cf_rows}
    ins('custom_field', 'id,name,type,possible_values,default_value,valid_regexp,access_level_r,access_level_rw,length_min,'
                        'length_max,require_report,require_update,display_report,display_update,display_resolved,display_closed,filter_by', cf_rows)
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
    ins_stream('bug', 'id,project_id,reporter_id,handler_id,duplicate_id,priority,severity,reproducibility,status,resolution,projection,eta,'
               'category_id,date_submitted,last_updated,os,os_build,platform,version,fixed_in_version,target_version,build,view_state,'
               'summary,sticky,description,steps_to_reproduce,additional_information', issue_rows())
    ins('custom_field_project', 'field_id,project_id,sequence',
        [(r['field_id'], r['project_id'], i(r['sequence'])) for r in cf_links if r['field_id'] in kept_cf])
    ins('custom_field_string', 'field_id,bug_id,value', [v for v in cf_values if v[0] in kept_cf])
    kept_tags = {t[0] for t in trows}
    ins('bug_tag', 'bug_id,tag_id', [x for x in bug_tags if x[1] in kept_tags])
    ins_stream('bugnote', 'id,bug_id,reporter_id,text,view_state,date_submitted,last_modified,note_type,time_tracking', note_rows())
    ins('bug_monitor', 'user_id,bug_id', monitors)
    ins('bug_relationship', 'id,source_bug_id,destination_bug_id,relationship_type', rels)
    ins_stream('bug_history', 'id,bug_id,user_id,field_name,old_value,new_value,type,date_modified', hist_rows())
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
    written['bug_file'] = nfiles
    print(f'  wrote {nfiles:7d}  bug_file   ({missing} skipped - no content; use --files-dir if Mantis stored files on disk)')

    # advance the id sequences so new records do not collide with imported ids
    for t in tables:
        if t in ('bug_tag',): continue
        dc.execute(f"SELECT setval(pg_get_serial_sequence('{full(t)}','id'), COALESCE((SELECT MAX(id) FROM {full(t)}), 1), "
                   f"(SELECT MAX(id) FROM {full(t)}) IS NOT NULL)")
    dst.commit()

    # ---------------------------------------------------------------- verify
    if warn:
        print('\nNotes from the import (rows that were skipped or adjusted on purpose):')
        for k, v in warn.items(): print(f'    {k}: {v}')
    fails = 0 if a.no_verify else reconcile(a, written, missing)
    print('\nDone. Start the backend and log in with an existing Mantis username + its old password.' if not fails else
          '\nIMPORT FINISHED BUT THE RECONCILIATION FAILED - do not go live before the differences are explained.')
    sys.exit(2 if fails else 0)

if __name__ == '__main__':
    main()
