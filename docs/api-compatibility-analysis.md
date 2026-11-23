# MantisBT API Compatibility Analysis Report

**Project**: MantisBT 2.28.4 → Java (Spring Boot 3.3) + Angular 18 + PostgreSQL 16  
**Repository**: `D:\Anand\bugTracker\mantisBT-phase2-repo\repo`  
**Analysis date**: Sun Nov 22 2026  
**Status**: Pre-implementation analysis. The implementation that followed is documented in
[`API_COMPATIBILITY.md`](API_COMPATIBILITY.md); see §11 below for the outcome.

> This report describes the repository **before** Phase 6. Statements such as "not implemented" in §5 are
> the baseline the implementation started from, not the current state.

---

## 1. Project Overview

The project is a Java port of the core MantisBT PHP bug tracker. The README confirms that **SOAP API and Mantis REST API compatibility are NOT yet migrated** (listed under "NOT migrated yet (phase 2)").

- **Backend**: Java 17, Spring Boot 3.3.5, Spring Data JPA (Hibernate), Spring Security + JWT, PostgreSQL 16
- **Frontend**: Angular 18 standalone components, lazy routes
- **Database schema**: JPA entities map to the original MantisBT table names (`mantis_bug_table`, `mantis_bugnote_table`, `mantis_bug_file_table`, `mantis_project_table`, `mantis_user_table`, etc.)
- **Running**: Backend on port 8080, frontend on port 4200 (proxies `/api` to backend)
- **Auth**: JWT Bearer token, login at `/api/auth/login` (accepts BCrypt and legacy MD5 passwords)

---

## 2. Backend Architecture — Package Structure

```
com.mantis
├── MantisApplication.java              (@SpringBootApplication, @EnableAsync)
├── config
│   ├── SecurityConfig.java             (Spring Security filter chain, CORS, JWT filter)
│   ├── ApiExceptionHandler.java        (@RestControllerAdvice: {"message": "..."} errors)
│   └── DataSeeder.java                 (default admin + sample project on first start)
├── domain                              (JPA entities, table names match MantisBT)
│   ├── Issue.java                      (mantis_bug_table + bug_text merged)
│   ├── Project.java                    (mantis_project_table, supports sub-projects)
│   ├── User.java                       (mantis_user_table)
│   ├── Note.java                       (mantis_bugnote_table + bugnote_text merged)
│   ├── Attachment.java                 (mantis_bug_file_table, bytea content, 5 MB limit)
│   ├── Category.java                   (mantis_category_table)
│   ├── ProjectVersion.java             (mantis_project_version_table)
│   ├── IssueRelationship.java          (mantis_bug_relationship_table)
│   ├── Tag.java                        (mantis_tag_table)
│   ├── CustomField.java                (mantis_custom_field_table, 11 types)
│   ├── CustomFieldValue.java           (mantis_custom_field_string_table)
│   ├── CustomFieldProject.java         (mantis_custom_field_project_table)
│   ├── IssueMonitor.java               (mantis_bug_monitor_table)
│   ├── IssueHistory.java               (mantis_bug_history_table)
│   ├── ProjectUser.java                (mantis_project_user_list_table)
│   ├── SavedFilter.java                (mantis_filters_table)
│   ├── News.java                       (mantis_news_table)
│   └── Lookups.java                    (Mantis enum constants: status 10-90, access levels, etc.)
├── dto
│   └── Dtos.java                       (All request/response records)
├── repo                                (Spring Data JPA repositories - 17 total)
├── security
│   ├── JwtService.java                 (JWT generate/validate, HS256)
│   ├── JwtAuthFilter.java              (OncePerRequestFilter, Bearer token → SecurityContext)
│   └── CurrentUser.java                (@Component: extracts authenticated User)
├── service
│   ├── IssueService.java               (create/update/status/search/delete/move/copy/workflow)
│   ├── IssueExtrasService.java         (notes, attachments, monitors, relationships, history views)
│   ├── AccessService.java              (Mantis access_api.php: per-project levels, private projects)
│   ├── CustomFieldService.java         (field definitions, per-issue values, validation)
│   ├── HistoryService.java             (audit trail)
│   ├── NotificationService.java        (e-mail after commit, access-aware recipients)
│   ├── BulkService.java                (one transaction per issue, reuses single-issue code)
│   ├── ProjectBulkService.java         (bulk categories/versions/members/copy)
│   ├── MailDispatcher.java             (SMTP e-mail sending)
│   └── ExportService.java              (CSV / Excel export)
└── web                                 (@RestController layer, all under /api)
    ├── IssueController.java            (/api/issues, /api/issues/{id}/*, /api/attachments/*)
    ├── ProjectController.java          (/api/projects, /api/projects/{id}/{categories,versions,members})
    ├── AuthController.java             (/api/auth/login, /api/auth/me, /api/auth/change-password)
    ├── UserController.java             (/api/users, /api/users/assignable, /api/users/lookup)
    ├── TagController.java              (/api/tags)
    ├── CustomFieldController.java      (/api/custom-fields, /api/projects/{pid}/custom-field-links/*)
    ├── MetaController.java             (/api/meta - enum labels for frontend)
    ├── NewsController.java             (/api/news)
    ├── ConfigController.java           (/api/config - feature switches)
    ├── IssueBulkController.java        (/api/issues/bulk, /api/issues/{id}/move, /api/issues/{id}/copy)
    ├── ProjectBulkController.java      (/api/projects/{pid}/categories/bulk, ...)
    ├── SavedFilterController.java      (/api/filters)
    └── TimeTrackingController.java     (/api/time-tracking)
```

---

## 3. Existing API Endpoints (All under `/api` prefix)

### Authentication
| Method | Path | Description | Auth |
|--------|------|-------------|------|
| POST | `/api/auth/login` | JWT login | None |
| GET | `/api/auth/me` | Current user | JWT |
| PUT | `/api/auth/preferences` | Update e-mail flag | JWT |
| POST | `/api/auth/change-password` | Change password | JWT |

### Meta / Configuration
| Method | Path | Description | Auth |
|--------|------|-------------|------|
| GET | `/api/meta` | Enum labels | JWT |
| GET | `/api/config` | Feature switches | JWT |

### Projects, Categories, Versions, Members, Issues, Notes, Attachments, Relationships, Monitors, History, Bulk/Move/Copy, Users, Tags, Custom Fields, Saved Filters, Dashboard, Export, Time Tracking — all documented in the analysis.

Full endpoint list: ~60+ endpoints covering all MantisBT core functionality (issue CRUD, project management, user management, categories, versions, tags, custom fields, notes, attachments, relationships, saved filters, bulk operations).

---

## 4. Authentication & Authorization Model

### Authentication
- **JWT Bearer tokens** issued at `/api/auth/login`
- `JwtAuthFilter` validates tokens, sets `SecurityContext`
- `CurrentUser` resolves the authenticated `User` entity
- Legacy MD5 password hashes upgraded to BCrypt on first login
- Sessions are STATELESS

### Authorization
- `AccessService` ports Mantis `access_api.php`
- Per-project overrides, sub-project inheritance, private project invisibility
- `access.canSee(User, Issue)` — private issues: reporter, handler, or DEVELOPER+
- `access.require(User, Project, minLevel)` — 404 if not visible, 403 if level too low

### Error Handling
- All errors: `{"message": "..."}` JSON
- No internal exception details leaked

---

## 5. Compatibility Gaps — What Is Missing

| Feature | Status |
|---------|--------|
| Mantis REST API (`/api/rest/`) | ❌ Not implemented |
| Mantis SOAP API + WSDL | ❌ Not implemented |
| API token authentication (separate from JWT) | ❌ Not implemented |
| Rate limiting | ❌ Not implemented |
| API request logging | ❌ Not implemented |
| REST/SOAP enable/disable config | ❌ Not implemented |
| Configurable attachment limits | ⚠️ Hardcoded 5 MB |
| API compatibility tests | ❌ Test directory empty |
| API documentation | ❌ Not present |

---

## 6. Reusable Components for API Compatibility

| Component | Reuse Role |
|-----------|-----------|
| `IssueService` | Issue CRUD, status transitions, workflow |
| `IssueExtrasService` | Notes, attachments, monitors, relationships |
| `AccessService` | Project permissions, private visibility |
| `JwtService` | Token validation |
| `CurrentUser` | Resolve authenticated user |
| `AccessService.canSee()` | Private issue/note checks |
| `ApiExceptionHandler` | Consistent error format |
| `Lookups.java` | Mantis enum values |
| `Dtos.java` / `Mapper.java` | DTO patterns |

---

## 7. Current Configuration Properties

In `application.yml`: `app.jwt.*`, `app.base-url`, `app.mail.*`, `app.time-tracking.enabled`, `app.cors-origins`, `app.seed.*`

No existing config for REST/SOAP toggle, API tokens, rate limiting.

---

## 8. Frontend Integration

Angular 18, `ApiService` wraps all `/api` endpoints, `auth.interceptor.ts` adds Bearer token, routes protected by guards.

---

## 9. Test Infrastructure

Backend test directory is **empty**. No existing tests.

---

## 10. Next Steps (Implementation Plan)

1. Add configuration properties for REST/SOAP API toggle, API tokens, rate limiting, attachment limits
2. Implement API token authentication (separate from JWT sessions)
3. Implement REST API compatibility layer under `/api/rest/` reusing existing services
4. Implement SOAP API compatibility layer under `/api/soap/` with WSDL
5. Add rate limiting and API request logging
6. Write comprehensive tests
7. Create documentation

---

## 11. Implementation outcome (Phase 6)

All gaps listed in §5 were addressed, except the ones explicitly documented as limitations:

| Gap | Outcome |
|-----|---------|
| Mantis REST API | ✅ `/api/rest/**` (users, projects, issues, notes, files, relationships, tags, monitors) |
| Mantis SOAP API + WSDL | ✅ `/api/soap` + `/api/soap/mantisconnect.wsdl` (10 operations) |
| API token authentication | ✅ `/api/tokens`, SHA-256 hashed, accepted next to JWT |
| Rate limiting | ✅ in-memory sliding window, configurable, HTTP 429 |
| API request logging | ✅ opt-in, metadata only |
| REST/SOAP enable/disable | ✅ `API_REST_ENABLED`, `API_SOAP_ENABLED`, `API_TOKEN_ENABLED` |
| Configurable attachment limits | ✅ `API_ATTACHMENT_MAX_BYTES` (web UI + REST) |
| API compatibility tests | ✅ 27 integration tests (H2 in PostgreSQL mode) |
| API documentation | ✅ [`API_COMPATIBILITY.md`](API_COMPATIBILITY.md) + Postman collection |

Deliberately not implemented (documented in `API_COMPATIBILITY.md` §5): SOAP attachments, relationships,
tags, custom fields, history, users, config/enums, filters; REST write access to custom fields; SOAP 1.2;
per-token scopes/expiry; shared (multi-instance) rate limiting; Flyway migrations.

Verification in this workspace: `cd backend && mvn clean verify` → 27 tests, 0 failures, exit 0;
`cd frontend && npm run build` → success. PostgreSQL/Docker were unavailable here, so the integration suite
runs against H2 in PostgreSQL compatibility mode; the production configuration is unchanged.
