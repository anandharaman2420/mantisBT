"""
End-to-end test of tools/import_mantis.py against a real (embedded) PostgreSQL:

   fixture MantisBT database  --import_mantis.py-->  schema generated from the JPA entities (entity_ddl.py)

Run:  pip install pgserver psycopg2-binary pymysql   &&   python -m unittest tools.tests.test_importer -v
      (or: cd tools/tests && python -m unittest test_importer -v)
"""
import datetime as dt, os, re, subprocess, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
IMPORTER = os.environ.get('MANTIS_IMPORTER') or os.path.join(ROOT, 'tools', 'import_mantis.py')   # override = mutation testing
DOMAIN = os.path.join(ROOT, 'backend', 'src', 'main', 'java', 'com', 'mantis', 'domain')

import mantis_fixture as fx
import entity_ddl
import pgserver, psycopg2

UTC = dt.timezone.utc


class ImporterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.srv = pgserver.get_server(os.path.join(cls.tmp, 'pg'))
        cls.host = re.search(r'host=([^&]+)', cls.srv.get_uri()).group(1)
        adm = psycopg2.connect(host=cls.host, user='postgres', dbname='postgres')
        adm.autocommit = True
        for d in ('src', 'dst', 'empty'):
            adm.cursor().execute(f'create database {d}')
        adm.close()
        cls.load_source()
        cls.ddl = entity_ddl.generate(DOMAIN)
        c = cls.conn('dst'); cur = c.cursor()
        for stmt in cls.ddl: cur.execute(stmt)
        c.commit(); c.close()

    # ---- the SOURCE (live Mantis) database: PostgreSQL here, MySQL in test_importer_mysql.py
    @classmethod
    def load_source(cls):
        c = cls.conn('src'); cur = c.cursor(); cur.execute(fx.SCHEMA); fx.populate(cur); c.commit(); c.close()

    @classmethod
    def source_args(cls):
        return ['--src-type', 'postgres', '--src-host', cls.host, '--src-db', 'src', '--src-user', 'postgres']

    @classmethod
    def src_conn(cls):
        return cls.conn('src')

    @classmethod
    def tearDownClass(cls):
        cls.srv.cleanup()

    # ---------------------------------------------------------------- helpers
    @classmethod
    def conn(cls, db):
        return psycopg2.connect(host=cls.host, user='postgres', dbname=db)

    def q(self, sql, params=None, db='dst'):
        c = self.conn(db)
        try:
            cur = c.cursor(); cur.execute(sql, params or ())
            return cur.fetchall() if cur.description else None
        finally:
            c.rollback(); c.close()

    def scalar(self, sql, params=None, db='dst'): return self.q(sql, params, db)[0][0]

    def run_importer(self, *extra, dst='dst'):
        cmd = [sys.executable, IMPORTER, *self.source_args(), '--dst-host', self.host, '--dst-db', dst, '--dst-user', 'postgres',
               '--dst-password', '', *extra]
        p = subprocess.run(cmd, capture_output=True, text=True)
        return p.returncode, p.stdout + p.stderr

    @classmethod
    def ensure_imported(cls):
        if getattr(cls, '_imported', False): return
        t = cls.__new__(cls); t.__dict__.update({})
        rc, out = ImporterTest.run_importer(t, '--wipe')
        assert rc == 0, out
        cls._imported, cls._out = True, out

    def setUp(self):
        self.ensure_imported()

    # ---------------------------------------------------------------- the checks
    def test_01_dry_run_reports_and_writes_nothing(self):
        t = self.__class__
        c = self.conn('empty'); cur = c.cursor()
        for stmt in self.ddl: cur.execute(stmt)
        c.commit(); c.close()
        rc, out = self.run_importer('--dry-run', dst='empty')
        self.assertEqual(rc, 0, out)
        self.assertIn('Dry run finished', out)
        self.assertIn('issues                                     7', out)
        self.assertEqual(self.scalar('select count(*) from mantis_user_table', db='empty'), 0)

    def test_02_row_counts(self):
        expected = {'user': 6, 'project': 8, 'project_user_list': 3, 'category': 6, 'project_version': 2, 'tag': 2,
                    'bug': 7, 'bug_tag': 2, 'bugnote': 4, 'bug_monitor': 2, 'bug_relationship': 3, 'bug_history': 3,
                    'news': 2, 'bug_file': 1, 'custom_field': 3, 'custom_field_project': 3, 'custom_field_string': 4}
        for t, n in expected.items():
            self.assertEqual(self.scalar(f'select count(*) from mantis_{t}_table'), n, t)

    def test_03_ids_are_preserved(self):
        self.assertEqual({r[0] for r in self.q('select id from mantis_bug_table')}, {1, 2, 3, 4, 6, 7, 8})
        self.assertEqual({r[0] for r in self.q('select id from mantis_user_table')}, {1, 2, 3, 4, 5, 6})
        self.assertEqual({r[0] for r in self.q('select id from mantis_project_table')}, set(range(1, 9)))
        self.assertEqual({r[0] for r in self.q('select id from mantis_bugnote_table')}, {1, 2, 3, 5})

    def test_04_users(self):
        u = {r[0]: r for r in self.q('select username, password, enabled, protected, access_level, last_visit, date_created, realname, email_enabled from mantis_user_table')}
        self.assertEqual(u['administrator'][1], fx.md5('root'), 'legacy MD5 hash must be copied untouched')
        self.assertTrue(u['administrator'][3]); self.assertEqual(u['administrator'][4], 90)
        self.assertFalse(u['olduser'][2])
        self.assertIsNone(u['anna'][5], 'last_visit 0 -> NULL')
        self.assertEqual(u['jsmith'][6], dt.datetime(2023, 7, 22, 4, 26, 40, tzinfo=UTC))
        self.assertEqual(u['zoe'][7], 'Zoë Müller')
        self.assertTrue(all(r[8] for r in u.values()), 'email_enabled defaults to true')

    def test_05_issues(self):
        b = {r[0]: r for r in self.q('select id, project_id, reporter_id, handler_id, duplicate_id, status, resolution, sticky, view_state, '
                                     'summary, date_submitted, last_updated from mantis_bug_table')}
        self.assertEqual(b[1][3], 2); self.assertEqual(b[1][5], 50)
        self.assertIsNone(b[2][3], 'handler_id 0 -> NULL')
        self.assertEqual(b[4][2], 1, 'missing reporter -> first administrator')
        self.assertEqual(b[4][4], 1); self.assertTrue(b[4][7]); self.assertEqual(b[4][6], 20)
        self.assertEqual(b[3][8], 50, 'private issue stays private')
        self.assertEqual(b[6][3], 5, 'a disabled handler is still kept')
        self.assertEqual(b[7][11], dt.datetime(1970, 1, 1, tzinfo=UTC), 'last_updated 0 -> epoch, not NULL')
        self.assertEqual(len(b[8][9]), 128)
        self.assertEqual(b[6][9], 'Café — résumé ☕')
        self.assertEqual(b[1][10], dt.datetime(2023, 7, 22, 4, 26, 40, tzinfo=UTC))

    def test_06_issue_texts_survive_exactly(self):
        t = {r[0]: r for r in self.q('select id, description, steps_to_reproduce, additional_information from mantis_bug_table')}
        self.assertEqual(t[4][1], 'He said "hello" and it\'s \\ broken; DROP TABLE x;--')
        self.assertEqual(t[7][1], 'multi\nline\r\nwith\ttab and emoji 😀')
        self.assertEqual(t[1][2], 'Open app\nWait 30 min\nClick')
        self.assertEqual(t[1][3], 'Seen on Linux')
        self.assertEqual(self.scalar("select summary from mantis_bug_table where id = 4"), "Closed 'quoted' \"double\" issue")

    def test_07_global_categories_are_copied_into_the_projects_that_use_them(self):
        cats = {r[0]: r for r in self.q('select id, project_id, name, user_id from mantis_category_table')}
        c1 = self.scalar('select category_id from mantis_bug_table where id = 1')
        self.assertGreater(c1, 7, 'a new category id (copy of the global "General")')
        self.assertEqual(cats[c1][1:3], (1, 'General'))
        c6 = self.scalar('select category_id from mantis_bug_table where id = 6')
        self.assertEqual(cats[c6][1:3], (1, 'Misc'))
        self.assertEqual(self.scalar('select category_id from mantis_bug_table where id = 2'), 4)
        self.assertIsNone(self.scalar('select category_id from mantis_bug_table where id = 4'))
        self.assertEqual(cats[2][3], 2, 'default assignee kept'); self.assertEqual(cats[5][3], 3)
        self.assertNotIn(7, cats, 'category of an unknown project is dropped')

    def test_08_hierarchy(self):
        h = {r[0]: r[1:] for r in self.q('select id, parent_id, inherit_parent from mantis_project_table')}
        self.assertEqual(h[2], (1, True)); self.assertEqual(h[7], (1, True), 'first of several parents')
        self.assertEqual(h[6], (5, True)); self.assertIsNone(h[5][0], 'cycle broken')
        for p in (1, 3, 4, 8): self.assertIsNone(h[p][0])

    def test_09_tags(self):
        self.assertEqual({r[0] for r in self.q('select name from mantis_tag_table')}, {'regression', 'ui'})
        links = self.q('select bug_id, t.name from mantis_bug_tag_table l join mantis_tag_table t on t.id = l.tag_id')
        self.assertEqual({(b, n) for b, n in links}, {(1, 'regression'), (1, 'ui')})

    def test_10_notes_and_time_tracking(self):
        n = {r[0]: r for r in self.q('select id, bug_id, reporter_id, text, view_state, note_type, time_tracking from mantis_bugnote_table')}
        self.assertEqual((n[3][5], n[3][6]), (2, 90)); self.assertEqual((n[1][5], n[1][6]), (0, 0))
        self.assertEqual(n[2][4], 50); self.assertEqual(n[2][3], 'private note')
        self.assertEqual(n[5][2], 1, 'missing note author -> administrator')

    def test_11_relationships_monitors_history(self):
        self.assertEqual(set(self.q('select source_bug_id, destination_bug_id, relationship_type from mantis_bug_relationship_table')),
                         {(1, 2, 1), (1, 3, 2), (1, 4, 0)})
        self.assertEqual(set(self.q('select user_id, bug_id from mantis_bug_monitor_table')), {(2, 1), (4, 1)})
        h = {r[0]: r for r in self.q('select id, user_id, field_name, old_value, new_value, type from mantis_bug_history_table')}
        self.assertEqual(set(h), {1, 2, 4})
        self.assertIsNone(h[2][1], 'history user 0 -> NULL'); self.assertEqual(h[1][2:5], ('status', '10', '50'))

    def test_12_attachments_news(self):
        f = self.q('select id, bug_id, filename, filesize, file_type, content from mantis_bug_file_table')[0]
        self.assertEqual((f[0], f[1], f[2], f[3], f[4], bytes(f[5])), (1, 1, 'hello.txt', 11, 'text/plain', b'hello world'))
        news = {r[0]: r for r in self.q('select id, project_id, poster_id, announcement, headline from mantis_news_table')}
        self.assertIsNone(news[2][1], 'global news'); self.assertEqual(news[2][2], 1); self.assertTrue(news[1][3])

    def test_13_custom_fields(self):
        f = {r[1]: r for r in self.q('select id, name, type, possible_values, require_report, access_level_r, access_level_rw from mantis_custom_field_table')}
        self.assertEqual(set(f), {'Customer', 'Severity Level', 'Browsers'})
        self.assertEqual(f['Severity Level'][3], 'Low|High'); self.assertTrue(f['Customer'][4]); self.assertFalse(f['Browsers'][4])
        v = {(a, b): c for a, b, c in self.q('select field_id, bug_id, value from mantis_custom_field_string_table')}
        self.assertEqual(v, {(1, 1): 'ACME corp', (2, 1): 'High', (3, 1): 'Chrome|Firefox', (3, 2): 'Safari'},
                         'multi values lose the leading/trailing |, the long "text" column is used when value is empty')
        self.assertEqual(set(self.q('select field_id, project_id from mantis_custom_field_project_table')), {(1, 1), (2, 1), (3, 2)})

    def test_14_sequences_continue_after_the_imported_ids(self):
        pid = self.scalar("insert into mantis_project_table (name, status, enabled, view_state, description) values ('New', 10, true, 10, '') returning id")
        self.assertEqual(pid, 9)
        uid = self.scalar("insert into mantis_user_table (username, email, password, enabled, protected, access_level, login_count) "
                          "values ('fresh', 'f@x.y', 'x', true, false, 25, 0) returning id")
        self.assertEqual(uid, 7)
        cid = self.scalar("insert into mantis_category_table (project_id, name) values (1, 'Brand new') returning id")
        self.assertEqual(cid, 10)       # copies took 8 and 9

    def test_15_reconciliation_passes_after_import(self):
        self.assertIn('RECONCILIATION: PASS', self._out)
        self.assertIn('content digest of every issue', self._out)
        self.assertNotIn('[FAIL]', self._out)

    def test_16_verify_only_passes_then_detects_tampering(self):
        rc, out = self.run_importer('--verify-only')
        self.assertEqual(rc, 0, out); self.assertIn('RECONCILIATION: PASS', out)
        for sql, label in [("update mantis_bug_table set description = description || ' ' where id = 1", 'changed text'),
                           ("update mantis_bug_table set priority = 40 where id = 2", 'changed priority'),
                           ("update mantis_bugnote_table set time_tracking = 91 where id = 3", 'changed time'),
                           ("update mantis_bug_table set last_updated = last_updated + interval '1 second' where id = 1", 'changed date'),
                           ("delete from mantis_bug_history_table where id = 1", 'lost history row')]:
            c = self.conn('dst'); cur = c.cursor(); cur.execute(sql); c.commit(); c.close()
            rc, out = self.run_importer('--verify-only')
            self.assertEqual(rc, 2, f'{label} must be detected\n{out}'); self.assertIn('RECONCILIATION: FAIL', out)
            rc, out = self.run_importer('--wipe')                      # restore the clean import
            self.assertEqual(rc, 0, out)

    def test_17_tables_that_are_empty_in_the_source_continue_with_id_1(self):
        c = self.src_conn(); cur = c.cursor()
        cur.execute("create table news_backup as select * from mantis_news_table"); cur.execute("delete from mantis_news_table"); c.commit()
        try:
            rc, out = self.run_importer('--wipe'); self.assertEqual(rc, 0, out)
            self.assertEqual(self.scalar('select count(*) from mantis_news_table'), 0)
            nid = self.scalar("insert into mantis_news_table (poster_id, view_state, announcement, headline, body) values (1, 10, false, 'h', 'b') returning id")
            self.assertEqual(nid, 1, 'an empty table must continue with id 1, not 2')
        finally:
            cur.execute("insert into mantis_news_table select * from news_backup"); cur.execute("drop table news_backup"); c.commit(); c.close()
            rc, out = self.run_importer('--wipe'); self.assertEqual(rc, 0, out)

    def test_20_second_import_needs_wipe(self):
        rc, out = self.run_importer()
        self.assertNotEqual(rc, 0); self.assertIn('--wipe', out)

    def test_21_missing_target_tables_are_explained(self):
        rc, out = self.run_importer(dst='postgres')
        self.assertNotEqual(rc, 0); self.assertIn('do not exist yet', out)

    def test_22_files_dir_imports_attachments_stored_on_disk(self):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, 'abcd1234_report.txt'), 'wb') as fh: fh.write(b'report')
        rc, out = self.run_importer('--wipe', '--files-dir', d)
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.scalar('select count(*) from mantis_bug_file_table'), 2)
        self.assertEqual(bytes(self.scalar('select content from mantis_bug_file_table where id = 2')), b'report')
        self.assertEqual(self.scalar('select filesize from mantis_bug_file_table where id = 2'), 6)
        rc, out = self.run_importer('--wipe')              # back to the baseline for the other tests
        self.assertEqual(rc, 0, out)

    def test_23_import_is_repeatable(self):
        for _ in range(2):
            rc, out = self.run_importer('--wipe'); self.assertEqual(rc, 0, out)
        self.assertEqual(self.scalar('select count(*) from mantis_bug_table'), 7)


if __name__ == '__main__':
    unittest.main(verbosity=2)
