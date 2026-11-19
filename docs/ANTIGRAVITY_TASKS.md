# Fortivance: parallel work brief for the Antigravity agent

Read this whole file before starting. Work through the tasks in order. Stop after each task so it can be reviewed and committed.

## Project context
Fortivance is a third-party risk tool for Indian banks. A vendor reports an incident; Fortivance traces which banking services are exposed (vendor -> subcontractor -> application -> business service), shows the RBI six-hour reporting window, and drafts a response plan that a human approves.

- Stack: Java 21, Spring Boot 3.3, MySQL 8.4, Flyway, Docker Compose. Front end: plain HTML/CSS/JS served from `src/main/resources/static/`. **No Angular, React or build step.**
- Run: `docker compose up --build`. App on http://localhost:8080. MySQL on host port 3307.
- Schema: see `src/main/resources/db/migration/V1..V3`. Demo users: `analyst` and `approver`, password `Fortivance@2026` (bcrypt hashes in `users`).
- Existing APIs: `GET /api/vendors`, `GET /api/vendors/{id}`, `GET /api/vendors/{id}/subcontractors`, `GET /api/impact/vendors/{id}`, `GET /api/dependencies/graph?incidentVendorId=`.

## Hard rules
1. **Never read, print or edit `.env`.**
2. **Do not edit** anything under `src/main/resources/db/migration/` or these packages: `com.fortivance.ai`, `com.fortivance.incident`, `com.fortivance.evidence`, `com.fortivance.dependency`. Another developer owns them. If you need a schema change, write the SQL in your task notes instead of adding a migration.
3. You own: `com.fortivance.security`, `com.fortivance.audit`, `com.fortivance.action`, `com.fortivance.metrics`, `com.fortivance.vendor` (may extend), `src/main/resources/static/**`, `scripts/**`, `pom.xml` (only the changes listed below), `README.md`.
4. No Lombok, no new dependencies beyond those named here.
5. Use `JdbcTemplate`/`NamedParameterJdbcTemplate` or JPA, whichever is simpler. Hibernate runs with `ddl-auto: validate`, so entity fields must match existing columns exactly.
6. After each task: `docker compose up --build` must start cleanly, and the listed checks must pass. Then stop and summarise what changed (file list plus one line each).

## Shared contracts (others code against these, so keep the signatures exactly)

```java
package com.fortivance.audit;
public interface AuditService {
    void log(String actor, String action, String entityType, Long entityId, java.util.Map<String, Object> detail);
}

package com.fortivance.security;
public final class CurrentUser {
    public static String username();   // "SYSTEM" when no authenticated user
    public static Long userId();       // null when no authenticated user
    public static String role();       // ANALYST, APPROVER, ADMIN or null
}
```

---

## Task A1: pom and security (do first)

`pom.xml`: add `spring-boot-starter-security` and `org.apache.pdfbox:pdfbox:3.0.3`. Do not use pdfbox yourself; it is for the incident pipeline.

Security:
- Load users from the `users` table (`UserDetailsService`, `BCryptPasswordEncoder`). Roles map to `ROLE_ANALYST`, `ROLE_APPROVER`, `ROLE_ADMIN`.
- Session-based form login using our own page `/login.html` (POST `/login`), logout at `/logout`.
- **Also enable HTTP Basic** so curl works: `curl -u analyst:Fortivance@2026 localhost:8080/api/vendors`.
- Permit without login: `/login.html`, `/css/**`, `/js/**`, `/vendor/**`, `/actuator/health`.
- Everything else requires login.
- Any POST path ending in `/approve` or `/reject` requires `ROLE_APPROVER` or `ROLE_ADMIN`.
- CSRF: disable for `/api/**` (demo app; note this in README as a known simplification).
- For `/api/**`, unauthenticated requests get **401 JSON**, not a redirect to the login page.
- `GET /api/me` returns `{ "id", "username", "displayName", "role", "team" }`.
- Implement `CurrentUser` as specified above.

Checks: `curl -i localhost:8080/api/vendors` gives 401; `curl -u analyst:Fortivance@2026 localhost:8080/api/me` gives the analyst; `/actuator/health` is open.

## Task A2: audit log

- `AuditService` implementation writing to the `audit_log` table (`detail` stored as JSON via Jackson).
- `GET /api/audit?entityType=&entityId=&limit=50` returns newest first.
- Log `LOGIN_SUCCESS` and `LOGIN_FAILURE` from security events.

Check: log in, then `GET /api/audit` shows `LOGIN_SUCCESS`.

## Task A3: actions API

Table `actions` already exists (see V3).
- `GET /api/actions?incidentId=&vendorId=&status=`
- `POST /api/actions` with `{ incidentId?, vendorId?, evidenceMappingId?, title, ownerTeam, assignedTo?, priority, dueAt? }`. Sets `source = MANUAL`.
- `PATCH /api/actions/{id}` with `{ status?, assignedTo?, ownerTeam?, dueAt? }`. Set `closed_at` when status becomes DONE.
- Every create and update calls `AuditService.log(...)`.
- Also expose `ActionService.createAiAction(Long incidentId, String title, String ownerTeam, String priority)` (sets `source = AI`) for the incident pipeline to call.

## Task A4: vendor profile and metrics APIs

`GET /api/vendors/{id}/profile` returns:
- vendor fields including `outsourcingScope`, `scopeNote`, `emailDomain`;
- parent vendor (if any) and subcontractors;
- documents (id, docType, title, issuedOn, validUntil, processingStatus);
- flags: `reviewOverdue` (next_review_due < today), `contractExpired` (contract_end < today), `possibleMisTier` (data_access in CUSTOMER_PII/FINANCIAL AND is_material = false AND any dependent application is CRITICAL);
- open actions count;
- applications that depend on this vendor.

`GET /api/metrics` returns these six (null when there is no data yet; never fail):

| key | definition |
|---|---|
| `evidenceCoveragePct` | evidence_mappings with status MET and review_status APPROVED or EDITED, divided by (in-demo requirements x vendors with at least one CONTRACT document), x100 |
| `dependencyVisibilityPct` | CRITICAL business services with at least one application that has at least one vendor dependency, divided by all CRITICAL services, x100 |
| `avgImpactAnalysisMinutes` | average minutes from incident `received_at` to the first `incident_events` row of type IMPACT_COMPUTED |
| `avgTimeToFirstActionMinutes` | average minutes from `received_at` to the first `actions.created_at` per incident |
| `expiredEvidenceCount` | documents with valid_until < today plus vendors with next_review_due < today |
| `openActions` | actions with status OPEN or IN_PROGRESS |

## Task A5: front end (plain HTML/JS)

Download `cytoscape.min.js` (3.x) into `static/vendor/` so the demo works offline. Use one shared `static/css/app.css` and `static/js/api.js` (fetch wrapper with credentials, 401 -> redirect to `/login.html`).

Visual style: clean, professional banking tool. Navy and teal, white cards, no emojis, no gradients. A small "AI-generated, needs review" badge style for anything AI-drafted.

Pages:
1. `login.html`: username/password form posting to `/login`.
2. `index.html`: dashboard. Six metric cards from `/api/metrics`, vendor table from `/api/vendors` with tier, scope and overdue flags, link to incidents.
3. `graph.html`: Cytoscape graph from `/api/dependencies/graph`. Layout left to right: services, applications, vendors, fourth parties (use `breadthfirst` or fixed columns by `type`). A vendor dropdown re-fetches with `?incidentVendorId=` and highlights affected nodes and edges in red; show the `/api/impact/vendors/{id}` summary in a side panel.
4. `vendor.html?id=`: vendor profile from A4.
5. `incidents.html` and `incident.html?id=`: **build against the mock file below** (`static/mock/incident-detail.json` and `static/mock/incidents.json`), behind a single `const USE_MOCK = true` flag in `api.js`. The real endpoints arrive later with the same shapes.
   - Detail page layout: header with vendor, severity and the **six-hour clock** (live countdown from `clock.deadline`; colours: green > 3h, amber 1-3h, red < 1h, grey with "Window elapsed" when negative). Label under the clock: **"Analyst decision required. Fortivance does not determine reportability."**
   - Left column: source notice text (monospace, scrollable) and extracted fields.
   - Right column: affected services table (DISRUPTED in red, DEGRADED in amber), response plan with the AI badge, Approve and Reject buttons (visible only when `/api/me` role is APPROVER), actions list, timeline.
   - Upload button on `incidents.html` posting multipart `file` to `POST /api/incidents` (disabled while `USE_MOCK`).

### Mock: `static/mock/incident-detail.json`
```json
{
  "id": 1,
  "referenceNo": "INC-NC-2026-1103",
  "status": "ASSESSED",
  "incidentVendor": { "id": 5, "name": "SecureVault Datacentres Pvt Ltd", "fourthParty": true },
  "reportedBy": { "id": 1, "name": "NimbusCloud Infra Pvt Ltd" },
  "senderEmail": "incidents@nimbuscloud.example",
  "incidentType": "DEGRADATION",
  "severityReported": "HIGH",
  "summary": "Cooling failure at SecureVault Mumbai facility; about 30% of NimbusCloud MUM-1 hosts throttled or powered down.",
  "detectedAt": "2026-11-03T12:40:00+05:30",
  "notifiedAt": "2026-11-03T16:05:00+05:30",
  "receivedAt": "2026-11-03T16:07:00+05:30",
  "clock": { "startsAt": "2026-11-03T12:40:00+05:30", "deadline": "2026-11-03T18:40:00+05:30", "notificationDelayMinutes": 205 },
  "sourceText": "INCIDENT NOTIFICATION - INITIAL\nFrom: NimbusCloud Infra Pvt Ltd ...",
  "extraction": { "model": "claude-haiku-5-5", "confidence": 0.92, "reviewStatus": "PENDING" },
  "impact": {
    "vendorChain": [
      { "id": 5, "name": "SecureVault Datacentres Pvt Ltd", "depth": 0 },
      { "id": 1, "name": "NimbusCloud Infra Pvt Ltd", "depth": 1 }
    ],
    "services": [
      { "id": 1, "code": "SVC-ACCT", "name": "Retail Accounts & Deposits", "criticality": "CRITICAL", "impactLevel": "DISRUPTED", "impactToleranceHours": 4, "viaApplications": ["CBS", "IBP", "MBB"] },
      { "id": 2, "code": "SVC-PAY", "name": "Digital Payments (UPI/IMPS)", "criticality": "CRITICAL", "impactLevel": "DISRUPTED", "impactToleranceHours": 2, "viaApplications": ["CBS", "MBB"] }
    ],
    "summary": { "services": 6, "disruptedServices": 5, "criticalServices": 4, "tightestImpactToleranceHours": 1, "singlePointsOfFailure": 2 }
  },
  "plan": {
    "aiOutputId": 10,
    "model": "claude-sonnet-5-5",
    "reviewStatus": "PENDING",
    "content": {
      "situationSummary": "A cooling failure at NimbusCloud's colocation subcontractor is degrading the Mumbai region that hosts core banking and digital channels.",
      "immediateActions": [
        { "title": "Confirm with NimbusCloud whether CBS database clusters have failed over", "ownerTeam": "Core Banking IT", "priority": "HIGH", "dueInMinutes": 30 }
      ],
      "decisionsForAnalyst": ["Assess whether this meets the RBI cyber incident reporting criteria"],
      "questionsForVendor": ["Which bank workloads were powered down, and when?"],
      "customerCommunication": "Hold; prepare a holding statement for digital banking delays."
    }
  },
  "actions": [
    { "id": 1, "title": "Confirm CBS failover status", "ownerTeam": "Core Banking IT", "status": "OPEN", "priority": "HIGH", "source": "AI" }
  ],
  "events": [
    { "type": "DETECTED_BY_VENDOR", "at": "2026-11-03T12:40:00+05:30", "actor": "SecureVault Datacentres Pvt Ltd", "description": "Cooling failure detected" },
    { "type": "NOTICE_RECEIVED", "at": "2026-11-03T16:07:00+05:30", "actor": "analyst", "description": "Notice uploaded" },
    { "type": "IMPACT_COMPUTED", "at": "2026-11-03T16:07:02+05:30", "actor": "SYSTEM", "description": "6 services affected, 5 disrupted" }
  ]
}
```
`static/mock/incidents.json`: an array of 3 summary objects `{ id, referenceNo, incidentVendorName, severityReported, status, detectedAt, clock.deadline, disruptedServices }` for the three notices in `sample-docs/incident-notices/`.

## Task A6: demo reset and README

- `scripts/reset-demo.sql`: delete rows from `actions`, `incident_events`, `incident_impacts`, `ai_outputs` (only where incident_id is not null), `incident_notices`, `audit_log`; keep everything else.
- `scripts/reset-demo.ps1` and `scripts/reset-demo.sh`: run that SQL inside the `fortivance-mysql` container using `docker exec` and the env vars from `.env` (read the variable names only; do not print values).
- README: add the new pages, APIs, demo users and the reset script.

