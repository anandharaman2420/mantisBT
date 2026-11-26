# Importing your live MantisBT data

The live Mantis database is only **read** - nothing is ever written to it, and the PHP Mantis keeps working.
The import is a **one-time copy** (re-run it with `--wipe` to rehearse as often as you like) followed by an automatic
**reconciliation** that compares source and target and fails loudly if anything differs.

## Steps

1. `pip install psycopg2-binary pymysql`
2. Start the backend once (`mvn spring-boot:run`) so the tables exist, then stop it.
3. **Dry run** - connects, validates, prints a report, writes nothing:
   ```
   python tools/import_mantis.py --src-type mysql --src-host <host> --src-db <db> --src-user <user> --src-password <pw> --dry-run
   ```
   * `--src-type postgres` if Mantis runs on PostgreSQL; `--src-prefix xyz` if your tables are not called `mantis_*_table`.
   * Umlauts come out as `Ã¼`? The old MySQL database stores latin1 - add `--src-charset latin1`.
4. **Real import** (empties the new database first, then copies everything, then reconciles):
   ```
   python tools/import_mantis.py --src-type mysql ... --dst-host localhost --dst-db mantis --dst-user postgres --dst-password ... --wipe
   ```
   If Mantis stored attachments **on disk** add `--files-dir /path/to/mantis/upload/folder`.
5. Read the output. `RECONCILIATION: PASS` means the target matches the source. Exit code 2 = something differs.
6. Start backend + frontend, log in with an existing Mantis user and its old password (MD5 hashes are upgraded to BCrypt on the
   first login).
7. Later, any time: `--verify-only` re-runs only the comparison (e.g. right before go-live, or to prove nothing was changed).

Run it against a backup (`mysqldump` / `pg_dump`) first if you can.

## What the reconciliation checks (with SQL on both sides, independent of the import code)

* users, projects, issues, issues **per project / status / priority**, notes, private notes, **time tracking minutes**, history rows, news
* a **SHA-256 digest over every issue** (id, project, status, resolution, priority, severity, visibility, sticky, dates, summary,
  description, steps, additional information) and **over every note** - one changed character, date or column mix-up fails it
* everything that was written is counted in the target
* informational only (legitimate differences possible, e.g. duplicate tag names, orphan rows): tags, custom fields, relationships, attachments

## What is dropped or adjusted on purpose (listed in the report with counts)

| Situation in Mantis | In the new system |
|---|---|
| issue in a project that does not exist | skipped |
| reporter / note author / news poster no longer exists | first administrator |
| `handler_id = 0` | no assignee (NULL) |
| history / tag / custom field user that no longer exists | NULL |
| global categories (project 0) | copied into every project that uses them; ids of normal categories are kept |
| project with several parents | first parent kept (this app has one parent per project) |
| hierarchy cycle | one link removed |
| duplicate tag / custom field name (case-insensitive) | second one skipped |
| relationship types 3 / 4 | stored as the equivalent flipped 2 / 0 |
| multi-value custom field stored as `\|a\|b\|` | `a\|b` |
| attachment without content in the database | skipped unless found with `--files-dir` |
| orphan rows (notes, history, monitors, ... of missing issues) | skipped |

Issue, note, project, user, tag, history ids are **kept**, so issue 0001234 stays 1234.

## Size and speed (measured)

50 000 issues + 300 000 notes + 500 000 history rows + 2 000 attachments: **~30 s from PostgreSQL (47 MB RAM), ~50 s from MySQL
(236 MB RAM)**, reconciliation included. The big tables are streamed, so memory does not grow with the database.

## Tests of the importer

```
pip install pgserver psycopg2-binary pymysql
cd tools/tests
python -m unittest test_importer -v                                  # source = PostgreSQL (embedded server, no setup)
MYSQLD=/path/to/mysqld python -m unittest test_importer_mysql -v     # source = real MySQL / MariaDB server binary
```
A realistic Mantis fixture (orphans, global categories, cycles, duplicates, flipped relationships, MD5 passwords, unicode, disk
attachments) is imported into a schema **generated from the JPA entities** (`entity_ddl.py`), so a renamed column or a wrong
column order is caught. Tampering tests prove the reconciliation fails when a text, priority, date, time entry or history row differs.
