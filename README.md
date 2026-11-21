# MantisBT 2.28.4 → Java (Spring Boot) + Angular + PostgreSQL

Port of the core of the PHP bug tracker MantisBT to:

| Layer | Technology |
|-------|-----------|
| Backend | Java 17, Spring Boot 3.3, Spring Data JPA (Hibernate), Spring Security + JWT |
| Frontend | Angular 18 (standalone components, lazy routes) |
| Database | PostgreSQL 16 – **tables are created automatically** (`spring.jpa.hibernate.ddl-auto=update`) |

## Run

```bash
docker compose up -d                 # PostgreSQL on :5432 (db/user/password = mantis)
cd backend  && mvn spring-boot:run   # API on :8080, creates all tables on first start
cd frontend && npm install && npm start   # UI on http://localhost:4200 (proxies /api to :8080)
```
Login: `administrator` / `root` (same default as MantisBT – change it!). A "Sample Project" is seeded.
Config via env vars: `DB_URL DB_USER DB_PASSWORD JWT_SECRET CORS_ORIGINS`.

## What was migrated (PHP → Java)

| MantisBT (PHP) | Java |
|---|---|
| `admin/schema.php` (ADOdb, 34 tables) | JPA entities in `domain/` – Hibernate generates the DDL. Table/column names kept (`mantis_bug_table`, `handler_id`, `date_submitted` …) |
| `core/constant_inc.php` enums (status 10…90, priority, severity, access levels) | `domain/Lookups.java` – **same integer values**, served to Angular at `/api/meta` |
| `core/access_api.php` | `service/AccessService` – global level + per-project override, private projects |
| `core/bug_api.php`, `bug_report.php`, `bug_update.php`, `bug_change_status_page.php` | `service/IssueService` – create / update / status workflow / delete, auto-assign, reopen → resolution "reopened", read-only resolved issues, private issues |
| `core/bugnote_api.php` | `IssueExtrasService` notes (public/private) |
| `core/file_api.php` | attachments stored in DB (`bytea`), 5 MB limit, served as download only |
| `core/history_api.php` | `HistoryService` – every change logged, shown with readable labels |
| `core/relationship_api.php`, monitor, tag, news, category, version, project_user_list | entities + controllers |
| `core/filter_api.php` / `view_all_bug_page.php` | `IssueService.search` (JPA Specification) + issue list page |
| `login.php`, MD5 passwords | JWT login; **legacy MD5 hashes are accepted and upgraded to BCrypt on first login** |
| `summary_page.php` | `/api/summary` dashboard |
| Smarty-less PHP pages | Angular pages: Dashboard, View Issues, Report/Edit Issue, Issue view, Manage Projects (details/categories/versions/members/news), Manage Users, Account |

Deliberate differences: `bug_table` + `bug_text_table` and `bugnote` + `bugnote_text` are merged into one entity each; "no handler" is `NULL` instead of `0`; real foreign keys are created.

## NOT migrated yet (phase 2)
Saved filters, time tracking/billing, sponsorship, plugin system, LDAP, SOAP API & Mantis REST API compatibility, CSV/Excel export, RSS, wiki, localisation (40 languages), project hierarchy/inheritance, move/clone issue, bulk actions.

## Phase 2 (done)

### E-mail notifications
Sent on: new issue, issue update / status change / assignment, new note. Recipients are the reporter, the handler and
everyone monitoring the issue – never the person who made the change, never users who switched notifications off
(*My Account*), and never users who may not see the issue (private issues / private notes).
Mails are sent asynchronously **after** the database commit; an SMTP failure never breaks the user's request.
Disabled by default. Enable with environment variables:

```
MAIL_ENABLED=true  MAIL_HOST=smtp.example.com  MAIL_PORT=587  MAIL_USER=...  MAIL_PASSWORD=...
MAIL_AUTH=true  MAIL_STARTTLS=true  MAIL_FROM=mantis@example.com  APP_BASE_URL=https://bugs.example.com
```

### Custom fields
Administrator defines fields (*Custom Fields* page); a project manager links them to projects
(*Manage Projects → Custom fields* or directly from *Custom Fields*). All 11 Mantis types: string, text area, integer, decimal, e-mail, date,
enumeration, list, radio, checkbox, multi-select. Per field: visible-from / editable-from access level, required on
report / update, min/max length (or value), regular expression, default value. Values are validated on the server and
changes are written to the issue history. The importer (`tools/import_mantis.py`) copies definitions, project links
and values from a live MantisBT database.

### Saved filters
Users can save current issue search criteria (status, priority, severity, category, assignee, text search, hide closed, sorting)
as named private or shared public filters. Quick one-click selection and application directly from the *View Issues* filter bar.
Persisted in `mantis_filters_table`.

### CSV and Excel export
Export current filtered issue lists to:
* **CSV** (`.csv`): Standard RFC 4180 format with UTF-8 BOM so Microsoft Excel and Google Sheets open special characters seamlessly.
* **Excel** (`.xls`): SpreadsheetML 2003 XML format with native styling (bold headers, colored header row, correct column widths and data types).

### Still open for phase 3
Time tracking, move/clone issue, bulk actions, project hierarchy, SOAP/REST compatibility, localisation.

## Important notes
* Existing databases: new columns/tables are added by `ddl-auto=update`; new columns carry DDL defaults so existing rows stay valid.
* The **frontend was built successfully** (`ng build`). The **backend could not be compiled in the sandbox** (no access to Maven Central); I checked the Java syntax of all files, but expect to fix a few small compile/runtime issues on your first `mvn spring-boot:run`.
* `ddl-auto=update` is convenient for development; for production switch to Flyway/Liquibase.
* To import existing MantisBT data, write an SQL script mapping `handler_id = 0 → NULL` and joining `bug_text`/`bugnote_text` into the merged tables.
