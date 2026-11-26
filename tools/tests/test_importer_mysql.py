"""
The same end-to-end tests as test_importer.py, but the live MantisBT database is a real MySQL / MariaDB server
(the most common Mantis setup). Needs a server binary:   MYSQLD=/path/to/mysqld python -m unittest test_importer_mysql -v
(conda-forge package `mysql` provides one; the tests are skipped when no mysqld is found).
"""
import os, re, shutil, subprocess, tempfile, time, unittest

import pymysql

import mantis_fixture as fx
import test_importer as base          # (module import: unittest must not collect the base class a second time)

MYSQLD = os.environ.get('MYSQLD') or shutil.which('mysqld') or shutil.which('mariadbd')
PORT = int(os.environ.get('MYSQL_TEST_PORT', '3307'))


def mysql_schema():
    """The PostgreSQL-flavoured fixture schema translated to MySQL (types only, same tables and columns)."""
    s = fx.SCHEMA.replace('serial primary key', 'int auto_increment primary key').replace(' bytea', ' longblob')
    s = re.sub(r'(\w+) text([,)])', r'\1 longtext\2', s)
    return [stmt.strip() for stmt in s.split(';') if stmt.strip()]


@unittest.skipUnless(MYSQLD, 'no mysqld / mariadbd found (set MYSQLD=/path/to/mysqld)')
class ImporterMySQLTest(base.ImporterTest):
    @classmethod
    def load_source(cls):
        data = os.path.join(cls.tmp, 'mysql')
        subprocess.run([MYSQLD, '--no-defaults', '--initialize-insecure', '--user=root', f'--datadir={data}'],
                       check=True, capture_output=True)
        cls.mysql = subprocess.Popen([MYSQLD, '--no-defaults', '--user=root', f'--datadir={data}', f'--port={PORT}',
                                      '--bind-address=127.0.0.1', f'--socket={os.path.join(cls.tmp, "my.sock")}', '--mysqlx=OFF',
                                      f'--pid-file={os.path.join(cls.tmp, "my.pid")}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(90):
            try:
                pymysql.connect(host='127.0.0.1', port=PORT, user='root', password='').close(); break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError('mysqld did not start')
        c = pymysql.connect(host='127.0.0.1', port=PORT, user='root', password='', autocommit=True)
        c.cursor().execute('create database mantis_src character set utf8mb4 collate utf8mb4_unicode_ci'); c.close()
        c = cls.src_conn(); cur = c.cursor()
        for stmt in mysql_schema(): cur.execute(stmt)
        fx.populate(cur); c.commit(); c.close()

    @classmethod
    def source_args(cls):
        return ['--src-type', 'mysql', '--src-host', '127.0.0.1', '--src-port', str(PORT), '--src-db', 'mantis_src', '--src-user', 'root']

    @classmethod
    def src_conn(cls):
        return pymysql.connect(host='127.0.0.1', port=PORT, user='root', password='', database='mantis_src', charset='utf8mb4')

    def test_00_the_source_really_is_mysql(self):
        c = self.src_conn(); cur = c.cursor(); cur.execute('select version()'); version = cur.fetchone()[0]; c.close()
        self.assertTrue(re.match(r'\d+\.\d+', version), version)
        self.assertIn('mysql', self.source_args())
        rc, out = self.run_importer('--dry-run', dst='empty')
        self.assertIn('Dry run finished', out)

    @classmethod
    def tearDownClass(cls):
        cls.mysql.terminate(); cls.mysql.wait(timeout=30)
        super().tearDownClass()


if __name__ == '__main__':
    unittest.main(verbosity=2)
