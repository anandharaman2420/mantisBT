# Phase 3 Plan — Production Hardening & Daily-Driver Parity

**Status:** proposal · **Date:** 2026-11-21 · **Base:** `phase-2` @ `f0b5447`

This plan turns the working Phase 2 port into something that can safely replace a live MantisBT
installation. It is deliberately split into **production readiness (P0)**, **features people use
every day (P1)**, and **ecosystem parity (P2 / Phase 4)** so the team can stop at a defensible
release boundary.

---

## 1. Executive summary

The application already covers the core bug-tracker loop: login, projects, issues, notes,
attachments, history, monitors, relationships, tags, news, custom fields, saved filters, CSV/Excel
export and e-mail notifications. Both halves build cleanly today:

| Check | Result |
|---|---|
| `cd backend && mvn -o -DskipTests compile` | ✅ succeeds |
| `cd frontend && npm run build` | ✅ succeeds (≈305 kB initial bundle) |
| Automated tests | ❌ none |
| CI pipeline | ❌ none |
| Database migrations | ❌ `ddl-auto=update` only |
| Production container images | ❌ none |

The biggest risk is **not missing features** — it is that there is no automated proof the port is
correct, no repeatable deployment, and no safe schema-change path. Phase 3 should fix that first,
then add the handful of features that block daily use (bulk actions, move/clone, time tracking).

**Recommended Phase 3 scope:** WS1–WS5 + the P0/P1 backlog below (~45–60 person-days).
Move project hierarchy, i18n, REST/SOAP compatibility, LDAP and plugins to Phase 4 unless a
customer contract requires them now.

### Progress log

**2026-11-21 — Sprint 0 (foundation) complete**

- [x] Compose/app credential mismatch fixed; `.env.example` and full-stack `docker-compose.yml`
      with health checks.
- [x] Backend and frontend Dockerfiles, nginx reverse proxy, `.dockerignore` files.
- [x] CI workflow (`.github/workflows/ci.yml`) for backend `mvn verify` and frontend build.
- [x] Flyway `V1__baseline.sql`; verified against an empty database **and** an existing
      Hibernate-created database; `ddl-auto=validate` is now the default.
- [x] `prod` profile with no default secrets plus a startup guard; verified that weak JWT secrets
      and the default admin password are rejected.
- [x] Actuator health/info; mail health is only checked when `MAIL_ENABLED=true`.
- [x] OpenAPI/Swagger UI with JWT bearer authorization.
- [x] Bounded exports (`EXPORT_MAX_ROWS`, default 50,000) and de-duplicated search/export helpers.
- [x] 21 unit tests: permission rules, export escaping, export limits, JWT handling.
- [x] Fixed the latent `ExportService.getOS()` compile bug hidden by stale class files.

**Next:** integration tests with Testcontainers, audit-safe issue deletion, refresh tokens/login
throttling, then bulk actions and move/clone.

**2026-11-22 — Security slice (Sprint 1, item 4 partly) and review fixes**

- [x] Login throttling / lockout for web login, change-password **and SOAP login** (`LoginAttemptService`):
      per user+address (5 failures / 15 min, lock doubles up to 24 h), per address (6x), per user from
      anywhere (6x, short non-escalating lock so nobody can be locked out for long). HTTP 429 + `Retry-After`.
      In-memory (per instance). 16 unit tests (`LoginThrottleTest`, fake clock, no Spring).
- [x] Unknown-user logins now spend the same BCrypt time as wrong passwords (no user enumeration by timing).
- [x] `server.forward-headers-strategy=native` default so the real client address is used behind nginx
      (without it all users share one address and 30 bad logins would lock everybody out).
- [x] API security headers (CSP `default-src 'none'`, frame-ancestors none, referrer policy, permissions policy, HSTS);
      nginx adds a strict SPA CSP. Angular production build no longer inlines critical CSS (`inlineCritical: false`)
      because its `onload` trick is an inline script that a strict CSP blocks.
- [x] Defect 6 (review): saved filters keep all selected statuses; reporter and tag are filter fields too;
      `/issues?tag=x` links work.
- [x] Fixed: clearing "fixed in" / "target" version did nothing (null meant "unchanged").
- [x] Backlog #25 (importer hardening + reconciliation): `tools/import_mantis.py` now streams issues/notes/history
      (1.1 GB -> 47 MB peak on 850k rows), reconciles source vs target (`--verify-only`, content digests, exit code 2),
      supports `--src-charset`, fixes the id sequence of tables that are empty in the source. 43 end-to-end tests
      (`tools/tests`, embedded PostgreSQL + real MySQL, schema generated from the JPA entities); CI job `importer`.
- [x] My View, Roadmap and Changelog pages (classic Mantis pages, no schema change). The grouping rules are plain Java
      (`ReleaseNotes`, 8 unit tests), the pages reuse the search visibility rules; `OverviewIntegrationTest` covers
      private-issue visibility, progress, ordering, access.
- [ ] Still open: refresh-token rotation and logout revocation (needs token storage or a per-user token version
      -> schema change, wait for the Flyway V2 migration), cluster-wide throttling (needs shared store).

---

## 2. Current state

### 2.1 What exists

| Area | Implementation |
|---|---|
| Backend | Spring Boot 3.3.5, Java 17 target, Spring Data JPA, Spring Security + JWT, JavaMail |
| Frontend | Angular 18 standalone components, lazy routes, signals, one inline-template component per page |
| Database | PostgreSQL 16, tables auto-created by Hibernate |
| Size | ~3.2k LOC Java, ~1.6k LOC TypeScript/HTML |
| Auth | JWT (8 h), BCrypt, legacy MD5 accepted and upgraded on first login |
| Issue workflow | create/update/status/delete, auto-assign, reopen, read-only resolved, private issues |
| Extras | notes (public/private), attachments (DB `bytea`, 5 MB), history, monitors, relationships, tags, news |
| Admin | projects, categories, versions, members, users, custom fields (11 types), saved filters, export |
| Ops | `docker-compose.yml` for PostgreSQL only; importer `tools/import_mantis.py`; README + `tools/IMPORT.md` |

### 2.2 Defects and gaps found during review

| # | Severity | Finding | Evidence |
|---|---|---|---|
| 1 | **High** | `docker compose up` + `mvn spring-boot:run` cannot connect out of the box: compose creates `mantis/mantis`, the app defaults to `postgres/root`. README claims both are `mantis`. | `docker-compose.yml:5-7`, `application.yml:6-8`, `README.md:14` |
| 2 | **High** | Deleting an issue hard-deletes its notes, attachments, monitors and **entire history** — audit trail is lost. | `IssueService.delete()` `IssueService.java:371-382` |
| 3 | **High** | No tests of any kind, including permission/visibility rules. | no `src/test` tree |
| 4 | **High** | Schema is only managed by `ddl-auto=update`; a production upgrade can silently alter/drop columns. | `application.yml:12` |
| 5 | **High** | Export loads **every matching issue** into memory and duplicates the search-parameter code three times. Large filters can OOM the JVM. | `IssueController.java:72-122`, `IssueService.searchAll()` |
| 6 | **Medium** | Saved filters only restore the **first** status (`c.status[0]`); `reporterId`/`tag` are not saved by the UI even though the DTO supports them. | `issue-list.component.ts:163,184-195` |
| 7 | **Medium** | Default JWT secret and default admin password are usable in production; no startup guard. | `application.yml:31,39-41` |
| 8 | **Medium** | No token refresh, logout/revocation, login rate-limiting or account lockout. | `AuthController.java`, `JwtService` |
| 9 | **Medium** | No `@Version` optimistic locking on `Issue`; concurrent edits silently overwrite each other. | `Issue.java` |
| 10 | **Medium** | E-mails are plain text with hard-coded English copy; no per-project/severity preferences or digest. | `MailDispatcher.java`, `NotificationService.java` |
| 11 | **Medium** | Missing indexes for the most common filters (`handler_id`, `reporter_id`, `category_id`, `last_updated`, text search). | `Issue.java:14-17` |
| 12 | **Low** | CSV export has 24 columns, Excel has 14 — inconsistent outputs. | `ExportService.java:23-29,117-118` |
| 13 | **Low** | Attachments are stored as `bytea` only; no storage abstraction, preview or scanning hook. | `IssueController.java:177-195` |
| 14 | **Low** | No API docs/contract; frontend types are hand-maintained. | `Dtos.java`, `models.ts` |

---

## 3. Phase 3 goals and non-goals

### Goals

1. **Reproducible deployment** — one command brings up app + database; images are versioned.
2. **Automated confidence** — CI runs backend, frontend and end-to-end tests on every push.
3. **Safe upgrades** — versioned migrations, tested from empty and from an imported Mantis DB.
4. **Secure defaults** — no shipped secrets, login throttling, revocable sessions, security headers.
5. **Daily-driver features** — bulk actions, move/clone, time tracking, correct saved filters.
6. **Cutover-ready migration** — rehearsed import with a reconciliation report and rollback plan.

### Non-goals for Phase 3 (proposed Phase 4)

- Plugin system, wiki, RSS, sponsorship/billing integration.
- Full MantisBT SOAP API and complete REST API compatibility.
- LDAP/SSO (unless required for cutover).
- 40-language localisation; Phase 3 only prepares the string-extraction groundwork if cheap.
- Project hierarchy/inheritance (defer unless large customers need it on day one).

---

## 4. Workstreams

### WS1 — Release engineering & configuration (P0)

**Why:** the app currently only runs on the original author's machine. This is the first thing a
reviewer or operator hits.

- Fix the compose/app credential mismatch; add `.env.example` and a `docker` Spring profile.
- Add multi-stage `backend/Dockerfile` (Maven build → JRE 17/21 runtime) and
  `frontend/Dockerfile` (Node build → nginx with `/api` proxy).
- Extend `docker-compose.yml` to app + db with healthchecks, named volumes and restart policies.
- Add `application-prod.yml`: `ddl-auto=validate`, no default secrets, JSON logging, actuator.
- Fail fast on weak `JWT_SECRET` / default admin password when the `prod` profile is active.
- Add Spring Boot Actuator (`/actuator/health`, `/actuator/info`) and expose only health publicly.
- Add `springdoc-openapi` so the API is discoverable and the frontend contract is visible.

**Done when:** `docker compose up` serves UI + API + DB from a clean checkout; no secret defaults
are used in the `prod` profile; `/actuator/health` is green in CI.

### WS2 — Test strategy & CI (P0)

**Why:** permission and workflow logic is the heart of the port and is currently unverified.

Test pyramid:

| Layer | Tooling | Coverage target |
|---|---|---|
| Unit | JUnit 5 + Mockito | `AccessService`, `IssueService` workflow, `CustomFieldService` validation, `ExportService` escaping |
| Integration | `@SpringBootTest` + Testcontainers PostgreSQL | auth, issue CRUD, visibility, notes, attachments, saved filters, export, notifications (mocked mail) |
| Contract | MockMvc / REST Assured | request/response shapes, validation errors, 401/403/404 behaviour |
| Frontend | Angular TestBed + Vitest/Jest (or Karma) | services, guards, key components |
| E2E | Playwright | login → report → assign → note → resolve → export |

CI (GitHub Actions, `.github/workflows/ci.yml`):

1. `backend`: `mvn -B verify` with Testcontainers (or a PostgreSQL service container).
2. `frontend`: `npm ci && npm run build && npm test -- --watch=false`.
3. `e2e`: docker compose up + Playwright smoke suite.
4. Optional: dependency/secret scanning (Dependabot, `dependency-check`, gitleaks).

Add seed fixtures (admin, reporter, developer, private project, private issue) so permission tests
are readable.

**Done when:** every push runs the pipeline; the critical-path suite covers login, report, update,
status, visibility, notes, attachments and export; a red test blocks merge.

### WS3 — Database lifecycle & performance (P0)

- Introduce **Flyway** with an initial baseline migration matching the current schema.
- Generate the baseline from a fresh Hibernate schema, then switch dev/prod to
  `ddl-auto=validate` (keep `update` only for a throwaway local profile if desired).
- Add forward migrations for every Phase 3 entity change; never edit applied migrations.
- Add indexes: `(project_id, status)`, `handler_id`, `reporter_id`, `category_id`, `last_updated`,
  and a trigram/full-text index for summary/description search (measure before choosing).
- Replace the three duplicated export/search parameter blocks with one criteria object.
- Cap or stream exports (e.g. 50k rows, or async job + download link) and add a `Content-Length`.
- Add `@Version` optimistic locking to `Issue` and return `409` on conflict.
- Add a soft-delete or "delete reason" path so audit history survives issue deletion (see defect 2).

**Done when:** a fresh DB and an imported DB both start with `ddl-auto=validate`; a 100k-issue
dataset lists and exports within agreed latency/memory budgets.

### WS4 — Security & account lifecycle (P0/P1)

- Remove default credentials from production config; require explicit env vars.
- Short-lived access token + refresh token (rotation), server-side revocation on logout and
  password change.
- Login rate limiting and temporary lockout (bucket4j or a small in-memory/Redis limiter).
- Password reset by e-mail with single-use, expiring tokens; optional self-registration matching
  Mantis signup, with admin approval if the instance is private.
- Password policy + breached-password check (optional).
- Security headers at nginx/Spring: HSTS, `X-Content-Type-Options`, `Referrer-Policy`,
  frame-ancestors, CSP for the Angular app.
- Audit admin actions (user/project/custom-field changes) in a dedicated table.
- Dependency and secret scanning in CI.

**Done when:** a security review of the auth flow passes; tokens can be revoked; brute-force login
is throttled; no secrets exist in the repository or default config.

### WS5 — Daily-driver feature parity (P1)

Ordered by user value per day of effort:

1. **Bulk actions** (M): multi-select in `View Issues`; bulk status/resolution/assign/priority/tag/
   monitor/delete. One server endpoint that checks permissions per issue and returns a
   partial-success report. Every change writes history and triggers notifications.
2. **Move & clone issue** (M): move to another project with category/version remapping and
   permission checks; clone with options (notes, attachments, relationships, tags). Both write
   history and notify.
3. **Saved-filter fixes** (S): preserve multiple statuses, persist `reporterId`/`tag`, add date and
   custom-field criteria, and make filters shareable/editable per role.
4. **Time tracking** (M): `time_tracking` on issues, `time_tracking`/`billing` on notes
   (`note_type=2` already exists in `Lookups`), totals per issue/project/user, a time report and
   CSV export. Map the corresponding Mantis columns in the importer.
5. **Notification improvements** (S/M): HTML templates, per-project and per-severity preferences,
   optional daily digest, `List-Unsubscribe`/reply-to headers.
6. **Attachment storage abstraction** (S/M): DB / filesystem / S3 behind one interface; keep the
   5 MB default but make it configurable; add image thumbnails and a malware-scan hook.
7. **Reporting dashboard** (M): open/overdue trends, average resolution time, workload by assignee,
   exportable.

### WS6 — Migration & cutover (P1)

- Harden `tools/import_mantis.py`: idempotent/resumable runs, `--dry-run` diff report, row-count
  and checksum reconciliation, attachment import (disk + DB), time tracking, custom fields,
  project hierarchy if added, tags, saved filters.
- Add an automated import test against a small Mantis-shaped fixture database.
- Rehearse on a **copy of production** twice; record timings and failures.
- Write a cutover runbook: freeze window, backup, import, validation queries, smoke tests,
  rollback (restore old Mantis + DB), and a communication plan.
- Define a post-cutover hypercare window (e.g. 2 weeks) with daily parity checks.

**Done when:** a production copy imports with 100% row parity on issues/notes/users/projects,
attachments resolve, and the runbook has been executed end-to-end in staging.

---

## 5. Prioritized backlog

Effort: **S** ≤ 2 days, **M** 3–5 days, **L** 6–10 days (one experienced full-stack developer).

| # | Item | WS | Priority | Effort | Depends on |
|---|---|---|---|---|---|
| 1 | ✅ Fix compose/app credential mismatch + `.env.example` | WS1 | P0 | S | — |
| 2 | ✅ Backend + frontend Dockerfiles, compose with healthchecks | WS1 | P0 | M | 1 |
| 3 | ✅ `prod` profile: no default secrets, `ddl-auto=validate`, actuator | WS1 | P0 | S | 1 |
| 4 | ✅ OpenAPI via springdoc | WS1 | P0 | S | 3 |
| 5 | 🚧 CI: backend verify + frontend build done; Playwright smoke pending | WS2 | P0 | M | 2 |
| 6 | 🚧 Unit tests done (21); fixtures + workflow/custom-field coverage pending | WS2 | P0 | L | 5 |
| 7 | Integration tests with Testcontainers (auth, CRUD, visibility, export) | WS2 | P0 | L | 6 |
| 8 | ✅ Flyway baseline + migration policy (verified empty + existing DB) | WS3 | P0 | M | 3 |
| 9 | Query indexes + `EXPLAIN` baseline on 100k issues | WS3 | P0 | M | 8 |
| 10 | ✅ Export cap (`EXPORT_MAX_ROWS`) + de-duplicated search parameters | WS3 | P0 | M | — |
| 11 | Optimistic locking on `Issue` (`@Version`, 409) | WS3 | P0 | S | 8 |
| 12 | Audit-safe delete (soft delete or retained history) | WS3 | P0 | M | 8 |
| 13 | Refresh/rotation + logout revocation | WS4 | P0 | M | 5 |
| 14 | Login rate limiting + lockout | WS4 | P0 | S | 5 |
| 15 | Password reset flow | WS4 | P1 | M | 5 |
| 16 | Security headers + CSP | WS4 | P1 | S | 2 |
| 17 | Admin action audit log | WS4 | P1 | M | 8 |
| 18 | Bulk actions (UI + API + tests) | WS5 | P1 | L | 6 |
| 19 | Move & clone issue | WS5 | P1 | M | 6 |
| 20 | Saved-filter fixes (multi-status, reporter/tag, date/CustomField) | WS5 | P1 | M | 6 |
| 21 | Time tracking (entity, totals, report, export) | WS5 | P1 | L | 8 |
| 22 | HTML e-mail templates + notification preferences | WS5 | P1 | M | 13 |
| 23 | Attachment storage abstraction + thumbnails | WS5 | P2 | M | 8 |
| 24 | Reporting dashboard | WS5 | P2 | M | 9 |
| 25 | Importer hardening + reconciliation report | WS6 | P1 | L | 8 |
| 26 | Production-copy rehearsal + cutover runbook | WS6 | P1 | M | 25 |
| 27 | Project hierarchy/inheritance | Phase 4 | P2 | L | 8 |
| 28 | i18n groundwork (string extraction, one extra locale) | Phase 4 | P2 | L | 4 |
| 29 | REST API compatibility subset + API tokens | Phase 4 | P2 | L | 4 |
| 30 | LDAP/SSO | Phase 4 | P2 | L | 13 |

---

## 6. Suggested delivery sequence

Assumes two-week sprints and one full-stack developer; parallelise with a second developer where
the workstreams do not overlap.

| Sprint | Focus | Exit criteria |
|---|---|---|
| **Sprint 0** | WS1 + start WS2/WS3: compose fix, Dockerfiles, prod profile, CI skeleton, Flyway baseline, OpenAPI | `docker compose up` works from clean checkout; CI is green on the skeleton |
| **Sprint 1** | WS2 + WS3: fixtures, unit/integration tests, indexes, export cap, optimistic locking, audit-safe delete | Critical-path suite green; `ddl-auto=validate` on fresh + imported DB; 100k-row list/export benchmark recorded |
| **Sprint 2** | WS4: refresh/revocation, rate limiting, password reset, headers, admin audit; WS5 saved-filter fixes | Security checklist signed off; saved filters round-trip all criteria |
| **Sprint 3** | WS5: bulk actions + move/clone | Both features covered by integration + E2E tests; history/notifications verified |
| **Sprint 4** | WS5: time tracking; WS6: importer hardening | Time report/export works; importer reconciliation passes on a Mantis fixture |
| **Sprint 5** | WS6: production-copy rehearsal, runbook, release candidate | Cutover rehearsal completed; go/no-go review |
| **Phase 4** | Hierarchy, i18n, REST/LDAP, plugins, reporting | Separate plan |

**Total estimate:** ~45–60 person-days for the recommended Phase 3 scope, plus contingency
(20–30%) for importer edge cases and permission regressions.

---

## 7. Release gates (Definition of Done)

A Phase 3 release is shippable only when all of these are true:

- [ ] CI is green on `main`; backend and frontend builds are reproducible.
- [ ] Backend unit + integration tests cover the critical path; coverage ≥ 70% on `service/` and
      `security/` (line coverage is a floor, not the goal).
- [ ] Playwright smoke suite passes against the Docker Compose stack.
- [ ] Flyway migrations apply cleanly to an empty DB and to an imported Mantis DB;
      `ddl-auto=validate` is active in `prod`.
- [ ] No default secrets; login throttling, token revocation, password reset and security headers
      verified.
- [ ] Import reconciliation: issues, notes, users, projects, attachments, custom fields and
      time-tracking rows match the source (100%).
- [ ] Backup and restore have been exercised; rollback runbook is written.
- [ ] p95 list/search latency and export memory are within the agreed budget on the 100k dataset.
- [ ] README, `tools/IMPORT.md` and the API docs match the shipped behaviour.

---

## 8. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| `ddl-auto=update` causes data loss during upgrade | High | Flyway baseline before any Phase 3 schema change; rehearse on a production copy |
| Permission regressions in bulk/move/clone | High | Shared permission helper + table-driven tests per role/project/issue state |
| Importer misses Mantis edge cases | High | Fixture DB + reconciliation counts; run on a copy twice before cutover |
| Export/search OOM on large instances | Medium | Cap/stream exports; add indexes; load-test 100k issues |
| Scope creep into full Mantis parity | Medium | Phase 3 boundary in §3; hierarchy/i18n/REST/LDAP deferred to Phase 4 |
| E-mail deliverability (SPF/DKIM, spam) | Medium | Document SMTP requirements; add HTML templates, reply-to and unsubscribe headers |
| Single-developer bus factor | Medium | CI, runbooks and migration docs are part of the deliverable, not afterthoughts |

---

## 9. Decisions needed before Sprint 0

1. **Deployment target:** on-prem Docker Compose, Kubernetes, or a managed platform? This changes
   the observability and secrets work.
2. **Cutover date and freeze window:** drives whether WS6 starts in Sprint 1 or Sprint 4.
3. **Must-have parity:** is project hierarchy required at cutover? Are SOAP/REST clients still in
   use? Is LDAP/SSO mandatory? Each answer moves an item from Phase 4 into Phase 3.
4. **Localisation:** which languages are actually needed at launch? Full i18n is a large effort;
   extracting strings early is cheap, translating 40 locales is not.
5. **Team size:** the sprint plan assumes one full-stack developer; with two, WS1/WS2 and WS4/WS5
   can run in parallel and compress the schedule by ~30–40%.
6. **Data retention/audit policy:** confirm that issue deletion must preserve history (recommended)
   and how long audit/login records are kept.

---

## 10. Immediate next actions (Sprint 1)

1. Add a Testcontainers PostgreSQL test harness and cover auth, issue CRUD and visibility rules.
2. Add reusable test fixtures (admin, reporter, developer, private project, private issue).
3. Decide and implement audit-safe issue deletion (recommended: soft delete with retained history).
4. Add refresh-token rotation, logout revocation and login rate limiting.
5. Add the V2 migration for query indexes and `Issue.@Version` optimistic locking; handle `409`.
6. Add a Playwright smoke suite to CI.
7. Confirm the decisions in §9 so the remaining sprints can be scheduled.
