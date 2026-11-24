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
Config via env vars: `DB_URL DB_USER DB_PASSWORD JWT_SECRET CORS_ORIGINS` (plus the `API_*` variables for the REST/SOAP layer).

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
Saved filters, time tracking/billing, sponsorship, plugin system, LDAP, CSV/Excel export, RSS, wiki, localisation (40 languages), project hierarchy/inheritance, move/clone issue, bulk actions.

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

## Phase 3 (in progress)

### Bulk actions, move and copy issue
* **View Issues**: tick issues in the first column, choose an action, *Apply*. Actions: assign, change status / resolve / close,
  priority, severity, visibility, category, "fixed in" / target version, sticky, add note, add / remove tags, move, copy, delete
  (max. 200 issues per request).
* Each issue is processed in **its own transaction** through the normal single-issue code, so permissions, workflow rules,
  history entries and e-mails are exactly the same as for a manual edit. Issues that fail (no access, read-only ...) are listed
  with the reason; the others are still done.
* **Move** (issue page → *Move / Copy…*): the issue keeps its number and history. Category is matched by name, the assignee is removed
  if he is not a developer in the target project, custom field values the target project does not use are dropped.
  Needs *developer* in the source and *reporter* in the target project.
* **Copy**: creates a new issue (status *new*, you as reporter, unassigned) incl. tags and custom fields; optionally copies notes and
  files and links it as "related to" the original.

### Time tracking
Developers can log time spent on a note (`1:30`, `90`, `1.5h`); the issue shows the total. Managers get a **Time Report** page
(per project and period, totals per user, CSV export). Same access rules as Mantis (view / edit: developer, report: manager).
On by default; `TIME_TRACKING_ENABLED=false` switches it off. The importer copies existing `time_tracking` values.

## Phase 4

### Project hierarchy (sub-projects)
A project can have a parent (*Manage Projects → Details*, administrator). The project selector shows the tree.

* Selecting a project shows the issues of **its sub-projects too** (list, export, dashboard, saved filters).
* With *inherit from parent* switched on (default) a sub-project also uses the parent's **categories, versions and
  custom fields** (shown as "(inherited)", editable only in the parent) and the **access level** a user has in the parent
  (so members of a private parent can see its sub-projects).
* Cycles are rejected; a project with sub-projects cannot be deleted.
* One parent per project (Mantis allowed several; the importer keeps the first and reports the others).
* `tools/import_mantis.py` reads `project_hierarchy` and sets parent + inherit flag.

## Phase 5: bulk project setup

For projects with hundreds of forms / many team members. *Manage Projects → (select project)*, manager role required.

| Where | What |
|---|---|
| **Categories → Bulk add** | paste a column from Excel (or upload .csv/.tsv/.txt), one category per line, optional 2nd column = default assignee. Up to 5000 rows. Existing names are skipped (assignee updated if given), duplicates and names that exist in a parent project are reported. |
| **Versions → Bulk add** | same, columns: version, description, released, obsolete |
| **Members → Bulk onboard** | **paste/upload a list** (`username` or e-mail + role: viewer / reporter / updater / developer / manager, default role for lines without one), **pick users** from a list with a role, or manage **current members** (remove selected, change role of selected). Administrators can also **create users that do not exist yet** (needs e-mail; one initial password for the batch). Roles above your own cannot be granted. |
| **Copy setup** | copy categories, versions, members & roles and custom fields from another project |

* One request = one transaction; a row with a problem (unknown user, name too long ...) is skipped and listed with its row number, the rest is applied.
* Column separator: *Auto* uses tabs when pasting from Excel and otherwise treats a whole line as one value, so names like `Form 12, Part B` stay intact. Quoted CSV is supported.
* Sample files: `docs/bulk-templates/`.

### Still open
Localisation.

## Phase 6: MantisBT REST & SOAP API compatibility

External tools can use a Mantis-compatible API in addition to the existing web UI. The layer translates
requests and reuses the normal services, so project permissions, private issues/notes, workflow, history and
notifications are enforced exactly as in the UI.

* **REST** under `/api/rest/**` (JSON, `snake_case` fields): users, projects/categories/versions, issue
  search/CRUD, notes, attachments, relationships, tags and monitors.
* **SOAP** (MantisConnect) at `/api/soap`, WSDL at `/api/soap/mantisconnect.wsdl`: `mc_version`, `mc_login`,
  `mc_projects_get_user_accessible`, `mc_project_get_issues`, `mc_issue_get`, `mc_issue_add`, `mc_issue_update`,
  `mc_issue_delete`, `mc_issue_notes_get`, `mc_issue_note_add`.
* **API tokens** for long-lived integrations (`/api/tokens`); only SHA-256 hashes are stored, the raw token is
  shown once. `Authorization: Bearer <jwt-or-token>`.
* Config: `API_REST_ENABLED`, `API_SOAP_ENABLED`, `API_TOKEN_ENABLED`, `API_LOGGING_ENABLED`,
  `API_RATE_LIMIT_ENABLED`, `API_RATE_LIMIT_RPM`, `API_ATTACHMENT_MAX_BYTES`.
* Tests: `cd backend && mvn test` (27 integration tests, H2 in PostgreSQL mode).

Full endpoint list, compatibility matrix, examples, Postman collection and limitations:
[`docs/API_COMPATIBILITY.md`](docs/API_COMPATIBILITY.md).

## Important notes
* Existing databases: new columns/tables are added by `ddl-auto=update`; new columns carry DDL defaults so existing rows stay valid.
* The **frontend was built successfully** (`ng build`). The **backend could not be compiled in the sandbox** (no access to Maven Central); I checked the Java syntax of all files, but expect to fix a few small compile/runtime issues on your first `mvn spring-boot:run`.
* `ddl-auto=update` is convenient for development; for production switch to Flyway/Liquibase.
* To import existing MantisBT data, write an SQL script mapping `handler_id = 0 → NULL` and joining `bug_text`/`bugnote_text` into the merged tables.


## Login protection and security headers

* **Login throttling** (`LoginAttemptService`) protects every place a password is checked: web login, change password and
  SOAP login. Per user + address 5 wrong passwords in 15 minutes lock that combination for 15 min, then 30, 60 ... up to
  24 h; one address trying many users and distributed guessing against one user are limited as well. The answer is
  HTTP 429 with `Retry-After`. Settings: `LOGIN_THROTTLE_ENABLED`, `LOGIN_MAX_FAILURES`, `app.security.login-throttle.*`.
  State is in memory: it resets on restart and is per instance.
* Behind nginx / a load balancer keep `FORWARD_HEADERS_STRATEGY=native` (default) so the real client address is used.
  Only private-range proxies are trusted, a client cannot spoof `X-Forwarded-For` when it connects directly.
* The API sends `Content-Security-Policy: default-src 'none'`, `X-Frame-Options: DENY`, a referrer and permissions policy
  and HSTS (HTTPS only); `frontend/nginx.conf` adds a strict CSP for the Angular app.
