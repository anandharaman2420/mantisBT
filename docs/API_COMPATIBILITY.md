# MantisBT REST & SOAP API Compatibility (Phase 6)

This document describes the Mantis-compatible API layer added in Phase 6.

* **REST** – `/api/rest/**` (JSON)
* **SOAP** – `/api/soap` (MantisConnect, SOAP 1.1), WSDL at `/api/soap/mantisconnect.wsdl`
* **API tokens** – `/api/tokens` (token management, JWT-authenticated)

The layer is a thin translation layer over the existing services. It does **not** duplicate business logic:
every create, update, transition, note, attachment, monitor, relationship and tag operation goes through
`IssueService`, `IssueExtrasService` and `AccessService`, so project permissions, private issues, private
notes, workflow rules, history and notifications behave exactly as in the web UI.

---

## 1. Configuration

All settings live under `app.api.*` in `backend/src/main/resources/application.yml` and can be overridden with
environment variables.

| Environment variable | Default | Meaning |
|---|---|---|
| `API_REST_ENABLED` | `true` | Expose `/api/rest/**` |
| `API_SOAP_ENABLED` | `true` | Expose `/api/soap` and the WSDL |
| `API_TOKEN_ENABLED` | `true` | Accept long-lived API tokens in the `Authorization` header |
| `API_LOGGING_ENABLED` | `false` | Log method, path, status and duration for `/api/rest` and `/api/soap` |
| `API_RATE_LIMIT_ENABLED` | `true` | Enable the per-instance sliding-window rate limiter |
| `API_RATE_LIMIT_RPM` | `120` | Requests per minute, per authenticated user or remote address |
| `API_ATTACHMENT_MAX_BYTES` | `5242880` (5 MB) | Hard upload limit, enforced for both the web UI and REST uploads |

The Spring multipart limit is separate (`spring.servlet.multipart.max-file-size: 5MB`). If you raise
`API_ATTACHMENT_MAX_BYTES` above 5 MB, raise the multipart limits too:

```bash
SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE=20MB
SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE=21MB
API_ATTACHMENT_MAX_BYTES=20971520
```

When a layer is disabled, its controllers are not registered at all (`@ConditionalOnProperty`), so the paths
return `404`. This is covered by `ApiDisabledIntegrationTest`.

---

## 2. Authentication

### 2.1 JWT (web UI, default)

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"administrator","password":"root"}'
```

Response:

```json
{ "token": "<jwt>", "user": { "id": 1, "username": "administrator", "accessLevel": 90, ... } }
```

Use it as:

```http
Authorization: Bearer <jwt>
```

### 2.2 API tokens (external tools)

Long-lived tokens are created by an authenticated user. The raw token is returned **once**; only its SHA-256
hash is stored in `mantis_user_api_token_table`.

```bash
# create (JWT required)
curl -s -X POST http://localhost:8080/api/tokens \
  -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' \
  -d '{"name":"ci pipeline"}'
```

```json
{ "token": "5f0c...48 hex chars...", "details": { "id": 3, "name": "ci pipeline", ... } }
```

```bash
# use it (Bearer is optional for API tokens)
curl -s http://localhost:8080/api/rest/users/me -H "Authorization: Bearer $API_TOKEN"
curl -s http://localhost:8080/api/rest/users/me -H "Authorization: $API_TOKEN"
```

Token management endpoints:

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/tokens` | List your tokens (never returns the secret) |
| `POST` | `/api/tokens` | Create a token; body `{"name":"..."}` (optional) |
| `DELETE` | `/api/tokens/{id}` | Revoke a token (your own; administrators may revoke any) |

If `API_TOKEN_ENABLED=false`, tokens can still be managed but are **not accepted** for authentication.

### 2.3 SOAP credentials

MantisConnect has no session. Every SOAP operation except `mc_version` must carry `username` and `password`
elements in the request body. The same password rules as the web UI apply (BCrypt and legacy MD5 hashes).

---

## 3. REST API

Base path: `/api/rest`. All responses are JSON, property names in `snake_case` (Mantis style).

### 3.1 Endpoints

| Method | Path | Permission | Description |
|---|---|---|---|
| `GET` | `/users/me` | authenticated | Current user |
| `GET` | `/users/{id}` | self or administrator | User details |
| `GET` | `/projects` | viewer on project | Projects the caller can see |
| `GET` | `/projects/{id}` | viewer | Project details |
| `GET` | `/projects/{id}/categories` | viewer | Categories (including inherited) |
| `GET` | `/projects/{id}/versions` | viewer | Versions (including inherited) |
| `GET` | `/projects/{id}/issues` | viewer | Issues of a project (`status`, `page`, `page_size`, `hide_closed`) |
| `GET` | `/issues` | viewer | Search issues (see filters below) |
| `GET` | `/issues/{id}` | viewer + issue visibility | Issue incl. visible notes |
| `POST` | `/issues` | reporter | Create an issue |
| `PATCH` | `/issues/{id}` | updater (developer for developer fields) | Update fields / status / resolution / handler |
| `DELETE` | `/issues/{id}` | developer | Delete an issue |
| `GET` | `/issues/{id}/notes` | viewer + note visibility | Notes (private notes filtered) |
| `POST` | `/issues/{id}/notes` | reporter | Add a note (private notes need developer) |
| `GET` | `/issues/{id}/files` | viewer | Attachment metadata |
| `GET` | `/issues/{id}/files/{fileId}` | viewer | Download an attachment (binary) |
| `POST` | `/issues/{id}/files` | reporter | Upload (`multipart/form-data`, field `file`) |
| `GET` | `/issues/{id}/relationships` | viewer | Relationships |
| `POST` | `/issues/{id}/relationships` | updater | Add `{"type":{"id":1},"issue":{"id":2}}` |
| `DELETE` | `/issues/{id}/relationships/{relationshipId}` | updater | Delete a relationship |
| `GET` | `/issues/{id}/tags` | viewer | Tags of the issue |
| `POST` | `/issues/{id}/tags` | updater | Attach `{"tag":{"name":"ui"}}` or `{"name":"ui"}` |
| `DELETE` | `/issues/{id}/tags/{tagId}` | updater | Detach a tag |
| `GET` | `/issues/{id}/monitors` | viewer | Monitors (developers see the list) |
| `POST` | `/issues/{id}/monitors` | reporter | Monitor as yourself or `{"user":{"id":N}}` |
| `DELETE` | `/issues/{id}/monitors/{userId}` | reporter/developer | Stop monitoring |

### 3.2 Issue search parameters (`GET /api/rest/issues`)

| Parameter | Type | Description |
|---|---|---|
| `project_id` | long | Project (includes sub-projects) |
| `status` | int (repeatable) | One or more status ids |
| `handler_id` | long | Assignee |
| `reporter_id` | long | Reporter |
| `priority` | int | Priority id |
| `severity` | int | Severity id |
| `category_id` | long | Category |
| `search` | string | Text search |
| `tag` | string | Tag name |
| `hide_closed` | boolean | Exclude resolved/closed |
| `page` | int, 1-based | Default `1` |
| `page_size` | int | Default `50`, max `100` |
| `sort` | enum | `id`, `lastUpdated`, `dateSubmitted`, `priority`, `severity`, `status`, `summary` |
| `dir` | `asc`/`desc` | Default `desc` |

Response shape:

```json
{
  "issues": [ { "id": 1, "project": {"id":1,"name":"Sample Project"}, "summary": "...", ... } ],
  "total_count": 42,
  "page": 1,
  "page_size": 50
}
```

The same total is also sent in the `X-Total-Count` response header for clients that follow the Mantis REST
convention. The JSON envelope is kept because it is easier for browsers and Postman to consume; see the
limitations in §8.

### 3.3 Create / update issue

Create (`POST /api/rest/issues`):

```json
{
  "project": { "id": 1 },
  "category": { "id": 1 },
  "summary": "Login button does nothing",
  "description": "Steps: ...",
  "steps_to_reproduce": "1. open page",
  "priority": { "id": 40 },
  "severity": { "name": "major" },
  "handler": { "id": 5 },
  "view_state": { "id": 10 },
  "tags": ["ui", "login"]
}
```

Enums accept `{"id": N}` or `{"name": "..."}` / `{"label": "..."}` (case-insensitive).

Update (`PATCH /api/rest/issues/{id}`) uses the same body. Omitted fields stay unchanged. A status change
goes through the normal workflow (`resolution`, `handler`, read-only resolved issues, manager-only reopen).

### 3.4 Errors

```json
{ "code": 400, "message": "description is required" }
```

| Status | Meaning |
|---|---|
| `400` | Invalid input / malformed JSON / bad enum |
| `401` | Missing or invalid credentials |
| `403` | Authenticated but not allowed |
| `404` | Not found, or hidden by permissions (private project/issue) |
| `409` | Database constraint conflict |
| `429` | Rate limit exceeded |
| `500` | Unexpected server error (details are logged, not returned) |

The REST error format is scoped to `com.mantis.api.rest`; the Angular UI keeps its existing `{"message":...}`
format.

---

## 4. SOAP API (MantisConnect)

* Endpoint: `POST /api/soap`
* WSDL: `GET /api/soap/mantisconnect.wsdl`
* Protocol: SOAP 1.1, document/literal, namespace `http://futureware.biz/mantisconnect`
* Content type: `text/xml`

### 4.1 Supported operations

| Operation | Description |
|---|---|
| `mc_version` | Server version string (no authentication) |
| `mc_login` | Validate credentials, return `UserData` |
| `mc_projects_get_user_accessible` | Projects the user can see |
| `mc_project_get_issues` | Issues of a project (`project_id`, `page_number`, `per_page`) |
| `mc_issue_get` | One issue incl. visible notes |
| `mc_issue_add` | Create an issue, returns the new id |
| `mc_issue_update` | Update an issue, returns `true` |
| `mc_issue_delete` | Delete an issue, returns `true` |
| `mc_issue_notes_get` | Visible notes of an issue |
| `mc_issue_note_add` | Add a note, returns the new note id |

### 4.2 Example: `mc_version`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:ns="http://futureware.biz/mantisconnect">
  <soapenv:Body>
    <ns:mc_version/>
  </soapenv:Body>
</soapenv:Envelope>
```

### 4.3 Example: `mc_issue_get`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:ns="http://futureware.biz/mantisconnect">
  <soapenv:Body>
    <ns:mc_issue_get>
      <username>administrator</username>
      <password>root</password>
      <issue_id>1</issue_id>
    </ns:mc_issue_get>
  </soapenv:Body>
</soapenv:Envelope>
```

```bash
curl -s -X POST http://localhost:8080/api/soap \
  -H 'Content-Type: text/xml' \
  --data-binary @issue-get.xml
```

### 4.4 Example: `mc_issue_add`

```xml
<ns:mc_issue_add>
  <username>reporter</username>
  <password>secret</password>
  <issue>
    <project><id>1</id></project>
    <category><id>1</id></category>
    <summary>Created over SOAP</summary>
    <description>Details</description>
    <priority><id>30</id></priority>
  </issue>
</ns:mc_issue_add>
```

Response (wrapped in the SOAP envelope):

```xml
<ns1:mc_issue_addResponse>
  <return>42</return>
</ns1:mc_issue_addResponse>
```

### 4.5 SOAP faults

Application errors are returned as SOAP 1.1 faults with HTTP `500`:

```xml
<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
  <SOAP-ENV:Body>
    <SOAP-ENV:Fault>
      <faultcode>SOAP-ENV:Client</faultcode>
      <faultstring>Access denied</faultstring>
    </SOAP-ENV:Fault>
  </SOAP-ENV:Body>
</SOAP-ENV:Envelope>
```

* `SOAP-ENV:Client` – bad request, bad credentials, unsupported operation
* `SOAP-ENV:Server` – permission/workflow/not-found/application error

Unknown operations return `Unsupported MantisConnect operation: ...` instead of a fabricated response.

### 4.6 Hardening

The SOAP body is parsed with a hardened DOM parser: DOCTYPE declarations and external entities are rejected
(XXE), and the secure-processing feature is enabled. Malformed XML returns a fault, not a stack trace.

---

## 5. Compatibility matrix

### 5.1 REST

| Area | Status | Notes |
|---|---|---|
| Login / JWT | ✅ | Existing `/api/auth/login` |
| Long-lived API tokens | ✅ | Create/list/revoke; SHA-256 hashed |
| Users (`me`, by id) | ✅ | Self or administrator |
| Projects / categories / versions | ✅ read-only | Includes inherited entries |
| Issue list / get / create / update / delete | ✅ | Reuses `IssueService` workflow |
| Issue search & pagination | ✅ | Filters listed in §3.2 |
| Notes | ✅ | Private notes filtered by role |
| Attachments | ✅ | Upload/list/download; size limit from config |
| Relationships / monitors / tags | ✅ | Reuses `IssueExtrasService` |
| Custom fields | ✅ read-only on issue | Values shown in `custom_fields` |
| Custom field write via REST | ❌ | Use the web UI; document limitation |
| History | ❌ | Available in the web UI (`/api/issues/{id}/history`) |
| Saved filters / news / config / enumeration | ❌ | Web UI only |
| Bulk operations / move / copy | ❌ | Web UI only |

### 5.2 SOAP (MantisConnect)

| Operation | Status |
|---|---|
| `mc_version` | ✅ |
| `mc_login` | ✅ |
| `mc_projects_get_user_accessible` | ✅ |
| `mc_project_get_issues` | ✅ basic pagination only (no filter object) |
| `mc_issue_get` | ✅ core fields + notes |
| `mc_issue_add` | ✅ |
| `mc_issue_update` | ✅ |
| `mc_issue_delete` | ✅ |
| `mc_issue_notes_get` | ✅ |
| `mc_issue_note_add` | ✅ |
| `mc_issue_attachment_*` | ❌ (REST only) |
| `mc_issue_relationship_*` | ❌ (REST only) |
| `mc_issue_tag_*` / `mc_tag_*` | ❌ (REST only) |
| `mc_project_get_categories` / `_versions` | ❌ (REST only) |
| `mc_user_*` | ❌ |
| `mc_config_*` / `mc_enum_*` | ❌ |
| `mc_filter_*` | ❌ |
| `mc_issue_get_history` | ❌ |
| Custom fields in SOAP | ❌ |
| SOAP 1.2 / `application/soap+xml` | ❌ (SOAP 1.1 only) |

The WSDL lists only the implemented operations. Clients that call anything else receive a SOAP fault.

---

## 6. Security review

| Concern | Handling |
|---|---|
| Project permissions | Every call goes through `AccessService.require` / `IssueService.getVisible`; no endpoint bypasses them |
| Private issues | `AccessService.canSee`: reporter, handler or developer+ only; others get `404` |
| Private notes | `IssueExtrasService.listNotes`: developer+ or the note author only |
| Privilege escalation | `IssueService` enforces developer-only fields, manager-only reopen/sticky, updater-only edits |
| Token storage | Only SHA-256 hashes are stored; the raw token is shown once |
| Token revocation | `DELETE /api/tokens/{id}`; user deletion removes tokens |
| Input validation | Service-layer validation (statuses, enums, lengths) plus JSON/XML parsing limits |
| Attachment size | Configurable hard limit before `IssueExtrasService`; Spring multipart limit as a second guard |
| Rate limiting | Sliding window per user/IP; `429` with a JSON body |
| Error leakage | REST returns `{"code","message"}`; unexpected errors are logged server-side, `500` returned |
| XXE / SSRF | DOCTYPE and external entities rejected; no URL fetching |
| CSRF | Stateless API, CSRF disabled as for the existing JWT API |
| Request logging | Off by default; logs method/path/status/duration only, never bodies or credentials |

**Known limitations**

* The rate limiter is **in-memory and per instance**. Behind a load balancer, use a shared store (Redis) or an
  API gateway.
* API tokens are bearer credentials: anyone who obtains one has the owner's permissions until it is revoked.
* SOAP credentials are sent in the body on every call; always use HTTPS in production.
* There is no per-token scope/expiry field yet (only enable/disable and revoke).
* `ddl-auto=update` creates the token table automatically; use Flyway/Liquibase in production.

---

## 7. Testing

### 7.1 Automated tests

```bash
cd backend
mvn test
```

The suite uses a real Spring context and an in-memory H2 database in PostgreSQL compatibility mode. It covers:

* authentication (JWT, API tokens, revoked tokens, disabled token auth)
* project and private-issue permissions with real users (reporter, developer, manager, administrator)
* private notes
* issue CRUD, status transitions and read-only resolved issues
* pagination, sorting, numeric/name enums
* notes and attachment upload with the configured size limit
* structured `400`/`404`/`429` errors
* SOAP version, login, issue get/add/update/delete, notes, faults, XXE rejection and WSDL
* REST/SOAP/token disabled configuration

Result: **27 tests, 0 failures**.

### 7.2 Manual REST checks

```bash
JWT=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"administrator","password":"root"}' | jq -r .token)

curl -s http://localhost:8080/api/rest/users/me -H "Authorization: Bearer $JWT"
curl -s http://localhost:8080/api/rest/projects -H "Authorization: Bearer $JWT"
curl -s "http://localhost:8080/api/rest/issues?page=1&page_size=20" -H "Authorization: Bearer $JWT"
```

### 7.3 Manual SOAP checks

```bash
curl -s http://localhost:8080/api/soap/mantisconnect.wsdl
curl -s -X POST http://localhost:8080/api/soap \
  -H 'Content-Type: text/xml' \
  --data-binary '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://futureware.biz/mantisconnect"><soapenv:Body><ns:mc_version/></soapenv:Body></soapenv:Envelope>'
```

### 7.4 Postman

Import `docs/postman/mantis-api-compatibility.postman_collection.json`. Set the collection variables
(`baseUrl`, `username`, `password`, `projectId`) and run **Auth → Login** first; the test script stores the JWT
in `{{token}}`. The **Auth → Create API token** request stores the raw token in `{{apiToken}}`.

### 7.5 Frontend

```bash
cd frontend
npm ci
npm run build
```

The compatibility layer is additive; the Angular UI uses the existing `/api/**` endpoints and is unchanged.

---

## 8. Environment limitations in this workspace

* **Docker is not installed** here, so the tests use H2 in PostgreSQL mode instead of a live PostgreSQL 16.
  The production configuration (`DB_URL`) is unchanged. To verify against PostgreSQL, start the database
  (`docker compose up -d`), run `mvn spring-boot:run`, and use the curl/Postman examples above.
* SOAP 1.2 is not implemented; clients must use SOAP 1.1 (`text/xml`).
* The REST layer follows Mantis-style paths and field names; it is not a byte-for-byte clone of a specific
  third-party Mantis REST plugin. Fields that the application does not store are omitted rather than invented.
  List endpoints return an `{"issues":[...],"total_count":...}` envelope (plus `X-Total-Count`) instead of a
  bare JSON array.
* No external SOAP/WSDL library was added; the endpoint uses the JDK XML parser and a hand-written WSDL, which
  keeps the dependency set unchanged.

---

## 9. Next steps

1. Add per-token scopes and expiry.
2. Move rate limiting to a shared store for multi-instance deployments.
3. Add SOAP attachments (`mc_issue_attachment_add/get`) and custom-field support if legacy clients need them.
4. Add REST write support for custom fields and saved filters.
5. Replace `ddl-auto=update` with Flyway migrations for production.
