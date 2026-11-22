# Importing your live MantisBT data

Your live Mantis database is only **read**. Nothing is written to it. The PHP Mantis keeps working as before.

1. `pip install psycopg2-binary pymysql`
2. Start the backend once (`mvn spring-boot:run`) so the tables exist, then stop it.
3. Dry run – checks the connection and prints a report, writes nothing:
   ```
   python tools/import_mantis.py --src-type mysql --src-host <host> --src-db <db> --src-user <user> --src-password <pw> --dry-run
   ```
   (use `--src-type postgres` if Mantis runs on PostgreSQL; add `--src-prefix xyz` if your tables are not called `mantis_*_table`)
4. Real import (empties the new database first, then copies everything):
   ```
   python tools/import_mantis.py --src-type mysql ... --wipe
   ```
   If attachments were stored on disk by Mantis, add `--files-dir /path/to/mantis/upload/folder`.
5. Start the backend + frontend. Log in with an existing Mantis user and its old password.
6. Compare: the script prints "issues by status" for source and target - they must match.

Run it against a copy/backup of the live DB first if you can (`mysqldump` / `pg_dump`).
