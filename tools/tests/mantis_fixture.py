"""
A small but nasty MantisBT 2.x database (PostgreSQL flavour: booleans are smallint, dates are unix timestamps)
used to test tools/import_mantis.py. Every table/column the importer reads exists; extra Mantis-only columns are included
on purpose. The data contains the cases that go wrong in real migrations: orphan rows, global categories (project_id = 0),
hierarchy cycles and multiple parents, duplicate tag / custom field names, relationship types 3 and 4, handler_id = 0,
a missing reporter, an attachment stored on disk, multi-value custom fields stored as "|a|b|", unicode, 128 char summaries.
"""
import hashlib

BASE = 1_690_000_000        # 2023-07-22T04:26:40Z
NOW = 1_700_000_000         # 2023-11-14T22:13:20Z


def md5(s): return hashlib.md5(s.encode()).hexdigest()


SCHEMA = """
create table mantis_user_table (id serial primary key, username varchar(191), realname varchar(191), email varchar(191),
  password varchar(64), enabled smallint, protected smallint, access_level smallint, login_count int, lost_password_request_count int,
  failed_login_count int, cookie_string varchar(64), last_visit int, date_created int);
create table mantis_project_table (id serial primary key, name varchar(128), status smallint, enabled smallint, view_state smallint,
  access_min smallint, file_path varchar(250), description text, category_id int, inherit_global int);
create table mantis_project_hierarchy_table (child_id int, parent_id int, inherit_parent int);
create table mantis_project_user_list_table (project_id int, user_id int, access_level smallint);
create table mantis_category_table (id serial primary key, project_id int, user_id int, name varchar(128), status int);
create table mantis_project_version_table (id serial primary key, project_id int, version varchar(64), description text,
  released smallint, obsolete smallint, date_order int);
create table mantis_tag_table (id serial primary key, user_id int, name varchar(100), description text, date_created int, date_updated int);
create table mantis_bug_text_table (id serial primary key, description text, steps_to_reproduce text, additional_information text);
create table mantis_bug_table (id serial primary key, project_id int, reporter_id int, handler_id int, duplicate_id int, priority smallint,
  severity smallint, reproducibility smallint, status smallint, resolution smallint, projection smallint, eta smallint, bug_text_id int,
  os varchar(32), os_build varchar(32), platform varchar(32), version varchar(64), fixed_in_version varchar(64), target_version varchar(64),
  build varchar(32), profile_id int, view_state smallint, summary varchar(128), sponsorship_total int, sticky smallint, category_id int,
  date_submitted int, due_date int, last_updated int);
create table mantis_bug_tag_table (bug_id int, tag_id int, user_id int, date_attached int);
create table mantis_bugnote_text_table (id serial primary key, note text);
create table mantis_bugnote_table (id serial primary key, bug_id int, reporter_id int, bugnote_text_id int, view_state smallint,
  date_submitted int, last_modified int, note_type int, note_attr varchar(250), time_tracking int);
create table mantis_bug_monitor_table (user_id int, bug_id int);
create table mantis_bug_relationship_table (id serial primary key, source_bug_id int, destination_bug_id int, relationship_type smallint);
create table mantis_bug_history_table (id serial primary key, user_id int, bug_id int, field_name varchar(64), old_value text,
  new_value text, type smallint, date_modified int);
create table mantis_bug_file_table (id serial primary key, bug_id int, user_id int, title varchar(250), description text, diskfile varchar(250),
  filename varchar(250), folder varchar(250), filesize int, file_type varchar(250), content bytea, date_added int);
create table mantis_news_table (id serial primary key, project_id int, poster_id int, view_state smallint, announcement smallint,
  headline varchar(64), body text, last_modified int, date_posted int);
create table mantis_custom_field_table (id serial primary key, name varchar(64), type smallint, possible_values text, default_value varchar(255),
  valid_regexp varchar(255), access_level_r smallint, access_level_rw smallint, length_min int, length_max int, require_report smallint,
  require_update smallint, display_report smallint, display_update smallint, display_resolved smallint, display_closed smallint, filter_by smallint);
create table mantis_custom_field_project_table (field_id int, project_id int, sequence smallint);
create table mantis_custom_field_string_table (field_id int, bug_id int, value varchar(255), text text);
"""

LONG_SUMMARY = ("S" * 128)


def populate(cur):
    def many(sql, rows):
        for r in rows: cur.execute(sql, r)

    many("insert into mantis_user_table values (%s,%s,%s,%s,%s,%s,%s,%s,%s,0,0,'',%s,%s)", [
        (1, 'administrator', 'Administrator', 'root@localhost', md5('root'), 1, 1, 90, 5, NOW, BASE - 5000),
        (2, 'jsmith', 'John Smith', 'jsmith@example.com', md5('dev123'), 1, 0, 55, 12, NOW - 100, BASE),
        (3, 'mrossi', 'Maria Rossi', 'mrossi@example.com', md5('mgr123'), 1, 0, 70, 3, NOW - 200, BASE),
        (4, 'anna', 'Anna Lee', 'anna@example.com', md5('rep123'), 1, 0, 25, 0, 0, BASE),          # last_visit = 0 -> NULL
        (5, 'olduser', 'Old User', 'old@example.com', md5('old'), 0, 0, 25, 1, NOW - 999, BASE),    # disabled
        (6, 'zoe', 'Zoë Müller', 'zoe@example.com', md5('zoe'), 1, 0, 40, 1, NOW, BASE)])
    many("insert into mantis_project_table values (%s,%s,%s,%s,%s,0,'',%s,0,1)", [
        (1, 'Alpha', 10, 1, 10, 'Main product'), (2, 'Alpha-Web', 10, 1, 10, 'Web client of Alpha'),
        (3, 'Secret', 10, 1, 50, 'Private project'), (4, 'Beta', 30, 0, 10, 'disabled project'),
        (5, 'CycA', 10, 1, 10, ''), (6, 'CycB', 10, 1, 10, ''), (7, 'TwoParents', 10, 1, 10, ''),
        (8, 'Ünïcode Project ☕', 10, 1, 10, 'Ünï')])
    many("insert into mantis_project_hierarchy_table values (%s,%s,%s)",
         [(2, 1, 1), (5, 6, 1), (6, 5, 1), (7, 1, 1), (7, 3, 0), (999, 1, 1)])
    many("insert into mantis_project_user_list_table values (%s,%s,%s)",
         [(3, 2, 55), (3, 3, 70), (1, 4, 25), (999, 2, 10), (1, 99, 10)])
    many("insert into mantis_category_table values (%s,%s,%s,%s,0)", [
        (1, 0, 0, 'General'), (2, 1, 2, 'UI'), (3, 1, 0, 'Backend'), (4, 2, 0, 'Frontend'), (5, 3, 3, 'Docs'),
        (6, 0, 0, 'Misc'), (7, 999, 0, 'Orphan')])
    many("insert into mantis_project_version_table values (%s,%s,%s,%s,%s,%s,%s)", [
        (1, 1, '1.0', 'first release', 1, 0, BASE), (2, 1, '2.0', '', 0, 0, NOW), (3, 999, 'x', '', 0, 0, NOW)])
    many("insert into mantis_tag_table values (%s,%s,%s,%s,%s,%s)", [
        (1, 2, 'regression', '', BASE, BASE), (2, 2, 'Regression', 'duplicate name', BASE, BASE), (3, 2, 'ui', '', BASE, BASE)])

    texts = [
        (1, 'Login fails after the session timeout.', 'Open app\nWait 30 min\nClick', 'Seen on Linux'),
        (2, 'Child project issue', '', ''),
        (3, 'Confidential', '', ''),
        (4, 'He said "hello" and it\'s \\ broken; DROP TABLE x;--', '', ''),
        (5, 'skipped: unknown project', '', ''),
        (6, 'unicode ☕ café', '', ''),
        (7, 'multi\nline\r\nwith\ttab and emoji 😀', 'steps', 'more'),
        (8, 'max length summary', '', '')]
    many("insert into mantis_bug_text_table values (%s,%s,%s,%s)", texts)
    #      id pr rep hnd dup prio sev rep st res prj eta txt os build...
    bug = ("insert into mantis_bug_table values (%s,%s,%s,%s,%s,30,%s,10,%s,%s,10,10,%s,%s,'','',%s,'',%s,'',0,%s,%s,0,%s,%s,%s,0,%s)")
    #      id, proj, rep, handler, dup,   sev, status, res, text, os, version, target, view, summary, sticky, cat, submitted, updated
    many(bug, [
        (1, 1, 4, 2, 0, 50, 50, 10, 1, 'Linux', '1.0', '2.0', 10, 'Login fails after timeout', 0, 1, BASE, NOW),
        (2, 2, 2, 0, 0, 50, 10, 10, 2, '', '', '', 10, 'Child project issue', 0, 4, BASE + 10, NOW),
        (3, 3, 3, 3, 0, 60, 20, 10, 3, '', '', '', 50, 'Private issue', 0, 5, BASE + 20, NOW),
        (4, 1, 99, 0, 1, 50, 90, 20, 4, '', '', '', 10, "Closed 'quoted' \"double\" issue", 1, 0, BASE + 30, NOW),
        (5, 999, 2, 0, 0, 50, 10, 10, 5, '', '', '', 10, 'Unknown project', 0, 0, BASE, NOW),
        (6, 1, 2, 5, 0, 70, 30, 10, 6, '', '', '', 10, 'Café — résumé ☕', 0, 6, BASE + 40, NOW),
        (7, 1, 4, 2, 0, 50, 40, 10, 7, '', '', '', 10, 'Multi-line description', 0, 3, BASE + 50, 0),
        (8, 1, 2, 0, 0, 50, 80, 20, 8, '', '', '', 10, LONG_SUMMARY, 0, 0, BASE + 60, NOW)])
    many("insert into mantis_bug_tag_table values (%s,%s,2,%s)", [(1, 1, BASE), (1, 3, BASE), (2, 2, BASE), (99, 1, BASE)])
    many("insert into mantis_bugnote_text_table values (%s,%s)", [
        (1, 'first note'), (2, 'private note'), (3, 'worked on it'), (4, 'orphan'), (5, 'reporter missing')])
    many("insert into mantis_bugnote_table values (%s,%s,%s,%s,%s,%s,%s,%s,'',%s)", [
        (1, 1, 2, 1, 10, BASE + 100, BASE + 100, 0, 0), (2, 1, 2, 2, 50, BASE + 200, BASE + 200, 0, 0),
        (3, 1, 2, 3, 10, BASE + 300, BASE + 300, 2, 90), (4, 99, 2, 4, 10, BASE, BASE, 0, 0),
        (5, 2, 99, 5, 10, BASE + 400, BASE + 400, 0, 0)])
    many("insert into mantis_bug_monitor_table values (%s,%s)", [(2, 1), (4, 1), (99, 1), (2, 999)])
    many("insert into mantis_bug_relationship_table values (%s,%s,%s,%s)", [
        (1, 1, 2, 1), (2, 3, 1, 3), (3, 4, 1, 4), (4, 1, 1, 1), (5, 1, 99, 1)])
    many("insert into mantis_bug_history_table values (%s,%s,%s,%s,%s,%s,%s,%s)", [
        (1, 2, 1, 'status', '10', '50', 0, BASE + 500), (2, 0, 1, 'handler_id', '0', '2', 0, BASE + 510),
        (3, 2, 99, 'status', '10', '20', 0, BASE), (4, 4, 1, '', '', '', 1, BASE)])
    many("insert into mantis_bug_file_table values (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", [
        (1, 1, 2, 'log', 'a log', 'x1', 'hello.txt', '', 11, 'text/plain', b'hello world', BASE),
        (2, 1, 2, 'report', '', 'abcd1234_report.txt', 'report.txt', '/nonexistent/uploads', 6, 'text/plain', b'', BASE),
        (3, 99, 2, 'orphan', '', 'x', 'o.txt', '', 1, 'text/plain', b'x', BASE)])
    many("insert into mantis_news_table values (%s,%s,%s,%s,%s,%s,%s,%s,%s)", [
        (1, 1, 2, 10, 1, 'Welcome', 'Release notes…', BASE, BASE), (2, 0, 99, 10, 0, 'Site news', 'poster missing', BASE, BASE)])
    many("insert into mantis_custom_field_table values (%s,%s,%s,%s,'','',10,25,0,0,%s,0,1,1,0,0,1)", [
        (1, 'Customer', 0, '', 1), (2, 'Severity Level', 3, 'Low|High', 0), (3, 'Browsers', 5, 'Chrome|Firefox|Safari', 0),
        (4, 'customer', 0, '', 0)])
    many("insert into mantis_custom_field_project_table values (%s,%s,%s)", [(1, 1, 1), (2, 1, 2), (3, 2, 1), (1, 999, 1), (4, 1, 3)])
    many("insert into mantis_custom_field_string_table values (%s,%s,%s,%s)", [
        (1, 1, 'ACME corp', ''), (2, 1, 'High', ''), (3, 1, '|Chrome|Firefox|', ''), (3, 2, '', '|Safari|'),
        (1, 5, 'bug 5 is skipped', ''), (1, 99, 'orphan', '')])
