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


---

# Round 2 (19 Nov): "Request a demo" UI, tasks A1 to A5

All earlier tasks above are complete. Do only the tasks in this section.

## What is being built
Public visitors no longer get shared demo credentials. Instead they request a demo: the form posts to the
backend, which stores the request, creates two personal demo accounts (analyst + approver, valid 24 hours)
and emails the credentials. **The backend, database, email and security are being built by another developer
at the same time. You build the UI only, against the API contract below.**

## Rules for this round (these replace the ownership rules above)
1. **Never read, print or edit `.env`.**
2. You may edit only: `src/main/resources/static/**` and `README.md`.
3. **Do not edit any Java file, `pom.xml`, `application.yml`, Docker files or migrations.**
4. Do not reformat files you touch. Change only the lines your task needs.
5. Keep the existing look: `/css/app.css`, the header with `/images/logo-icon.svg`, `/favicon.svg`.
6. No frameworks, no CDN scripts, no build step. Plain HTML, CSS and JS.
7. Stop after each task and list the files changed (one line each).

## API contract (the backend implements this; code against it exactly)

`POST /api/demo-requests` (public, JSON)
```json
{ "email": "jane@acme.com", "name": "Acme Bank", "phone": "+91 98765 43210", "purpose": "...", "website": "" }
```
| Field | Rule |
|---|---|
| `email` | required, valid email, max 200 chars |
| `name` | required, 2 to 100 chars (person or organisation name) |
| `phone` | optional; if present 7 to 20 chars, only digits, spaces and `+ - ( )` |
| `purpose` | required, **50 to 1000 chars** ("who you are and why you need access") |
| `website` | honeypot: hidden from people, always sent empty |

Responses (all JSON):
| Status | Body | Show the user |
|---|---|---|
| 201 | `{ "status": "sent" }` | Success panel: "Thank you. Your demo credentials are on their way to <email>. Check your inbox (and spam folder) in the next few minutes. Access is valid for 24 hours." Hide the form. |
| 400 | `{ "error": "...", "field": "purpose" }` | Show `error` under the named field |
| 409 | `{ "error": "..." }` | Show `error` (an access request already exists for this email) |
| 429 | `{ "error": "..." }` | Show `error` |
| 503 / 502 / network | `{ "error": "..." }` or none | "We could not process your request right now. Please try again later." |

Use `window.fetch` directly (not `api.js`, which redirects to login on 401).

## Task A1: `request-demo.html` (new page)
- Public page at `/request-demo.html`. Same header as `about.html` (logo, "Fortivance", "Log in" button on the right; no app nav links).
- Short intro: "Request a demo of Fortivance. We will email you two personal accounts (an analyst and an approver) so you can try the full incident workflow. Access lasts 24 hours and runs on synthetic data."
- Form fields, in order: Email, Name or organisation, Phone (optional), "Who are you and why do you need access?" (textarea).
- Browser-side checks matching the table above, shown inline under each field on blur and on submit. Do not submit while any field is invalid.
- Live character counter under the textarea: "0 / 1000 (minimum 50)", turns green once at 50 or more.
- Hidden honeypot field `website`: position it off screen (not `display:none`), `tabindex="-1"`, `autocomplete="off"`, `aria-hidden="true"` on its wrapper.
- Submit button shows "Sending..." and is disabled while the request is in flight.
- Handle every response in the table above.
- Footer link back to `/about.html`.
- Checks: page loads while logged out; empty submit shows errors on all required fields; a 49-character purpose is rejected; phone `abc` is rejected; phone left empty is accepted.

## Task A2: `about.html`
- **Remove the "Contact us" section (`<section id="contact">`), its `.contact-form` CSS and the `initContactForm` script.** The request form replaces it.
- Hero buttons:
  - Logged out: "Request a demo" (primary, to `/request-demo.html`) and "Log in" (secondary, to `/login.html`).
  - Logged in: keep the current "Triage an incident" and "Review vendor evidence" buttons.
  - The page already detects login via `GET /api/me`; build on that. Make sure the existing code that rewrites `.hero-actions a` hrefs to `/login.html` for logged-out visitors does not overwrite the "Request a demo" link.
- Add a small closing section above "About this instance": heading "Try Fortivance", one line of text, and a "Request a demo" button (shown only when logged out).
- Checks: logged out shows "Request a demo"; logged in shows the original buttons; no console errors; the live metrics still load.

## Task A3: `login.html`
- Remove the line that shows the demo accounts and the password.
- Below the form add: "Don't have access? <a href="/request-demo.html">Request a demo</a>".
- If the URL has `?error=expired`, show: "Your demo access has expired. Reply to your welcome email to ask for an extension." (style it like the existing error message). Keep the existing `?error` and `?logout` messages working.
- Checks: `/login.html?error=expired` shows the expiry message; `/login.html?error` still shows the normal error; no password appears anywhere on the page.

## Task A4: email-safe logo
- Create `src/main/resources/static/images/email-logo.png`: the Fortivance logo (icon plus the word "Fortivance", from `images/logo.svg`) as a PNG, about 240 x 64 px, transparent or white background.
- Email clients (Gmail, Outlook) block SVG, so this must be a real PNG file. If you cannot produce a PNG, say so and skip this task; do not save an SVG with a `.png` name.

## Task A5: README
- Remove the "Demo Accounts" table and every mention of the old `analyst` / `approver` password.
- Add a short "Request a demo" section: visitors request access at `/request-demo.html`; the system emails two personal accounts valid for 24 hours; requests are stored, and each email address can request once.
- Mention `/request-demo.html` in the pages list and `POST /api/demo-requests` (Public) in the API table. Remove `/api/contact` from the docs if it is listed.
- Do not include any password.

## Task A6: `profile.html` (new page, logged-in users only)

Backend contract (built by the other developer):

`GET /api/me/demo` (logged in)
- 200 for demo accounts:
```json
{
  "email": "jane@acme.com", "name": "Acme Bank", "phone": "+91 98765 43210", "purpose": "...",
  "requestedAt": "2026-11-19T18:05:00+05:30",
  "accessExpiresAt": "2026-11-20T18:05:00+05:30",
  "analystUsername": "acmebank-analyst", "approverUsername": "acmebank-approver",
  "extensionCount": 0, "maxExtensions": 2, "canExtend": true
}
```
- 404 `{ "error": "..." }` for accounts that are not demo accounts (owner and judge accounts).

`POST /api/me/demo/extend` (logged in, no body)
- 200 with the same body as above (new `accessExpiresAt`, `extensionCount` + 1).
- 409 `{ "error": "Maximum extensions reached" }` when `extensionCount` has reached `maxExtensions`.

Page:
- Normal app page: same header and nav as `index.html`, `api.initNav('profile')`. Logged-out visitors are sent to login (as `api.js` already does on 401).
- Card "Your demo access": email, name or organisation, phone (show "Not provided" if empty), requested at, and the purpose text (preserve line breaks; insert it with `textContent`, never `innerHTML`).
- Card "Accounts": the two usernames, each labelled Analyst / Approver. Never show passwords.
- Card "Access period": expiry date and time in IST (for example "20 Nov 2026, 6:05 PM IST"), plus a live "expires in 5 h 12 min" countdown, and "Extensions used: 0 of 2". Each extension adds 24 hours, and extending applies to both accounts.
- Button "Request 24-hour extension":
  - If `canExtend` is false, disable it and show "Maximum extensions reached".
  - On click, ask for confirmation, call `POST /api/me/demo/extend`, then update the card from the response. On 409 show the error and disable the button.
- If `GET /api/me/demo` returns 404: show only "Signed in as <username>. This account does not expire." (no extension button).
- In `js/api.js` `initNav`, make the user's name a link to `/profile.html` (keep the Logout link as it is).
- Checks: name in header links to the profile page on every page; a 404 response shows the "does not expire" message; the button state follows `canExtend`; no console errors.

---

# Round 3 (20 Nov): email login, role switch, forgot password, tasks U1 to U5

Rounds 1 and 2 are complete. Do only the tasks in this section.

## What changes
- **One account per person, and it can switch role.** A demo request now creates a single account whose
  username **is the email address** (for example `jane@acmebank.com`) plus a generated password. The owner and
  judge accounts are also single accounts now: `bhavya` and `judge`. Every account can switch between
  **Analyst** and **Approver** from the header; the choice is remembered for the next login.
- **Forgot password** sends a one-time reset link by email (valid 30 minutes). Demo accounts only.
- The backend (Java, database, email, security) is being built by another developer at the same time against
  the contract below. Build the UI only.

## Rules (same as Round 2)
1. **Never read, print or edit `.env`.**
2. Edit only `src/main/resources/static/**`. No Java, `pom.xml`, `application.yml`, Docker files or migrations.
3. Do not reformat files you touch; change only what the task needs.
4. Keep the existing look (`/css/app.css`, header logo, favicon). Plain HTML/CSS/JS, no frameworks or CDNs.
5. Insert any server-provided text with `textContent`, never `innerHTML` (the only exception is the existing
   `initNav` template, which already escapes with `escapeHtml`).
6. Stop after each task and list the files changed (one line each).

## API contract (code against this exactly)

**`GET /api/me`** (logged in)
```json
{ "id": 12, "username": "jane@acmebank.com", "displayName": "Acme Bank", "role": "ANALYST", "team": "Acme Bank", "canSwitchRole": true }
```

**`POST /api/me/role`** (logged in), body `{ "role": "APPROVER" }` (or `"ANALYST"`)
- 200 `{ "role": "APPROVER" }`: the session now has the new role. Reload the page so role-dependent UI
  (for example the Approve/Reject buttons on `incident.html`) updates.
- 400 `{ "error": "..." }` unknown role; 403 `{ "error": "..." }` account cannot switch.

**`POST /api/demo-requests`** (public), same request body as before. The 201 response changes to:
```json
{ "status": "sent", "username": "jane@acmebank.com", "password": "Kp7mQx2RtW9aZe", "role": "ANALYST",
  "accessExpiresAt": "2026-11-21T18:05:00+05:30" }
```
(`analystUsername`, `analystPassword`, `approverUsername` and `approverPassword` no longer exist.)

**`GET /api/me/demo`** (logged in), 200 for demo accounts:
```json
{ "email": "jane@acmebank.com", "name": "Acme Bank", "phone": null, "purpose": "...",
  "requestedAt": "2026-11-20T18:05:00+05:30", "accessExpiresAt": "2026-11-21T18:05:00+05:30",
  "username": "jane@acmebank.com", "role": "ANALYST",
  "extensionCount": 0, "maxExtensions": 2, "canExtend": true }
```
404 for permanent accounts (unchanged). `POST /api/me/demo/extend` returns the same shape (otherwise unchanged).

**`POST /api/password/forgot`** (public), body `{ "email": "jane@acmebank.com" }`
- 200 `{ "status": "sent", "message": "If an account exists for this email, we have sent a reset link. It is valid for 30 minutes." }`.
  This is returned whether or not the account exists. Always show `message`, and never suggest whether the
  email is registered.
- 400 `{ "error": "...", "field": "email" }` invalid email; 429 `{ "error": "..." }` too many requests.

**`POST /api/password/reset`** (public), body `{ "token": "<from the URL>", "newPassword": "..." }`
- 200 `{ "status": "changed", "message": "Your password has been changed. You can now sign in." }`
- 400 `{ "error": "...", "field": "token" }` link invalid, expired or already used;
  400 `{ "error": "...", "field": "newPassword" }` weak password; 429 `{ "error": "..." }`.

Reset links look like `https://fortivance.in/reset-password.html?token=<64 hex characters>`.
Password rules (same as change password): 10 to 64 characters, at least one letter and one number.

Use `window.fetch` directly on the public pages (`forgot-password.html`, `reset-password.html`,
`request-demo.html`); `api.fetch` redirects to login on 401.

## Task U1: `login.html`
- Username field: label "Email or username", placeholder "you@company.com". Keep `name="username"`, the `id`
  and the existing autocomplete; make sure `type="text"` (owner and judge usernames are not emails). Add
  `autocapitalize="none"` and `spellcheck="false"`.
- Put a "Forgot password?" link to `/forgot-password.html` on the **right-hand side of the Password label row**
  (label on the left, link on the right, same line). Small, teal, no underline until hover.
- Do not change the eye toggle, the error/expired/logout messages or the form action.
- Checks: the label row shows "Password" on the left and "Forgot password?" on the right; the eye toggle still
  works; logging in still works.

## Task U2: `forgot-password.html` (new, public)
- Same header and card style as `request-demo.html` (logo, "Log in" button on the right, no app nav).
- Heading "Reset your password", then: "Enter the email address you used to request your demo. We will send
  you a link to choose a new password." Below it in small grey text: "Owner and judge accounts are managed by
  the administrator."
- One email field (same validation as `request-demo.html`); submit button "Send reset link" (shows
  "Sending..." and is disabled while in flight).
- On 200: hide the form and show a green panel with the `message` from the response and a "Back to sign in"
  button. On 400 show the error under the field; on 429 or anything else show the error in a red banner.
- Footer link "Back to sign in" to `/login.html`.

## Task U3: `reset-password.html` (new, public)
- Same header and card style. Heading "Choose a new password".
- Read `token` from the query string. If it is missing or is not 64 hex characters, show only a red panel
  "This reset link is invalid. Request a new one." with a link to `/forgot-password.html`.
- Fields "New password" and "Confirm new password", each with the same eye show/hide toggle as `login.html`
  (copy its markup, CSS and script pattern; the toggles are independent; switch both back to hidden on
  submit). Hint: "10 to 64 characters, with at least one letter and one number."
- Client checks before submitting: length, a letter and a number, both fields match.
- POST `{ token, newPassword }`. On 200: hide the form and show a green panel with `message` and a "Sign in"
  button to `/login.html`. On 400 with `field: "token"`: show the invalid-link panel above with its text
  replaced by `error`. Other 400: show `error` under the new-password field. 429 and others: red banner.
- After the page loads, remove the token from the address bar with `history.replaceState` (keep it in a
  variable) so it is not left in the browser history.

## Task U4: role switch in the header (`js/api.js`, `initNav`)
- When `user.canSwitchRole` is true, replace the role badge with a small two-option switch
  `[ Analyst | Approver ]`: the current role highlighted (teal background, white text), the other a plain
  button. When `canSwitchRole` is false or missing, keep the existing badge exactly as it is now.
- Clicking the inactive option: disable the switch, `POST /api/me/role` with `{ "role": "APPROVER" }` or
  `{ "role": "ANALYST" }` (send `Content-Type: application/json`), then `window.location.reload()` on 200. On
  an error, re-enable it and `alert()` the `error` text.
- Use `<button>` elements with `aria-pressed`, and a `title` such as "Switch to Approver".
- Add the CSS for the switch to `css/app.css`. It must fit in the dark header next to the user name and wrap
  sensibly on narrow screens.
- The user name stays a link to `/profile.html`; Logout stays as it is.
- Checks: the switch appears on every app page; switching to Approver shows Approve/Reject on a pending plan in
  `incident.html`; switching back hides them.

## Task U5: `request-demo.html` and `profile.html`
`request-demo.html`
- Success panel: replace the two account boxes with **one** box "Your sign-in details": Email (`username`)
  and Password (`password`), each with the existing Copy button. Below it: "You start as an Analyst. Switch to
  Approver at any time from the header after signing in." Keep the expiry line and the "save these now" note.
- Intro text: "We will email you a personal account (sign in with your email) so you can try the full incident
  workflow as an analyst and as an approver. Access lasts 24 hours and runs on synthetic data."

`profile.html`
- Replace the "Accounts" card with a "Sign-in" card: "Email / username" (`username`) and "Current role"
  (`role`, shown as "Analyst" or "Approver"), plus the line "Switch role from the header at any time."
- Change-password card: remove the sentence "Your other demo account keeps its own password." and keep
  "Changes the password of the account you are signed in as (...)".
- Access-period card: replace "extending applies to both accounts" with "Each extension adds 24 hours."
- Remove every remaining reference to `analystUsername` / `approverUsername`.
- Checks: a demo account shows one sign-in card with its email and role; permanent accounts still show
  "This account does not expire"; no console errors.

---

# Round 4 (20 Nov): separate admin console, tasks AD1 to AD4

Rounds 1 to 3 are complete. Do only the tasks in this section.

## What changes
- The old admin panel (`/admin.html`, the "Admin" header link) is **removed by the other developer**. Do not
  restore it and do not edit `js/api.js` for this round.
- **`bhavya` becomes a dedicated admin account.** It signs in only at the hidden page **`/admin/login`** and
  uses a separate admin console at **`/admin/`**. It can no longer sign in on `/login.html`.
- **`judge` is renamed `judgedemo`**: a normal permanent test account (Analyst/Approver switch, no expiry, no
  admin rights).
- Nothing on the public site may link to `/admin/...`. Logged-out visitors and normal users get a plain 404 there.
- The backend (Java, security, database) is built by another developer at the same time against the contract
  below. Build the UI only.

## Rules (same as before)
1. **Never read, print or edit `.env`.**
2. Edit only `src/main/resources/static/**`. No Java, `pom.xml`, `application.yml`, Docker files or migrations.
3. Do not reformat files you touch; change only what the task needs.
4. Plain HTML/CSS/JS, no frameworks or CDNs. Reuse `/css/app.css`, `/favicon.svg`, `/images/logo-icon.svg`.
5. Insert every server-provided value with `textContent` (or `createElement`), never `innerHTML`. Names,
   purposes and emails come from public forms.
6. Do **not** load `/js/api.js` on admin pages (it redirects to the user login). Use `window.fetch` with
   `credentials: 'same-origin'`.
7. Stop after each task and list the files changed (one line each).

## File locations and URLs
| File | URL |
|---|---|
| `static/admin/login.html` | `/admin/login` (the server forwards this URL to the file) |
| `static/admin/index.html` | `/admin/` |
| `static/admin/admin.css` | `/admin/admin.css` (optional, admin-only styles) |
| `static/admin/admin.js` | `/admin/admin.js` |

Every admin page must have `<meta name="robots" content="noindex, nofollow">` in `<head>`.

## API contract (code against this exactly)

**Admin sign-in** is a normal HTML form post (not fetch):
```html
<form method="post" action="/admin/login">
  <input name="username"> <input name="password" type="password">
</form>
```
On failure the server redirects to `/admin/login?error`; after 5 failed attempts from the same network it
redirects to `/admin/login?error=locked`; after signing out, `/admin/login?logout`. Success goes to `/admin/`.
Sign out: a plain link to **`/admin/logout`**.

**CSRF for admin actions.** The server sets a cookie named `XSRF-TOKEN`. Every admin `POST` and `DELETE` must
send its value in the header `X-XSRF-TOKEN`:
```js
function csrfToken() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : '';
}
```
`GET` requests do not need it.

**Session ended or not an admin:** every `/api/admin/**` call then answers **404** `{ "error": "Not found" }`.
On any 404 from these endpoints, go to `/admin/login`.

**`GET /api/admin/me`** -> `{ "username": "bhavya", "displayName": "Bhavya Pant" }`

**`GET /api/admin/accounts`** (add `?includeDeleted=true` to include deleted accounts)
```json
{
  "generatedAt": "2026-11-20T18:05:00+05:30",
  "counts": { "ACTIVE": 3, "EXPIRING_SOON": 1, "EXPIRED": 2, "DISABLED": 1, "DELETED": 0 },
  "accounts": [
    {
      "userId": 12, "requestId": 4, "type": "DEMO",
      "username": "jane@acmebank.com", "email": "jane@acmebank.com", "name": "Acme Bank",
      "phone": "+91 98765 43210", "purpose": "Who they are and why they need access...",
      "role": "ANALYST", "state": "ACTIVE", "active": true,
      "requestedAt": "2026-11-20T10:00:00+05:30", "accessExpiresAt": "2026-11-21T10:00:00+05:30",
      "minutesLeft": 960, "extensionCount": 1, "maxExtensions": 2, "adminExtensionHours": 48,
      "lastLoginAt": "2026-11-20T12:30:00+05:30", "deletedAt": null
    }
  ]
}
```
- `type`: `DEMO` (requested through the form, expires) or `TEST` (permanent test account such as `judgedemo`;
  `requestId`, `accessExpiresAt`, `minutesLeft`, `phone`, `purpose` are null).
- `state`: `ACTIVE`, `EXPIRING_SOON` (under 6 hours left), `EXPIRED`, `DISABLED`, `DELETED`, or for a request
  whose welcome email failed, `NOT_ACTIVATED`.
- Any value may be null; show "-" for null.

**Actions** (send `X-XSRF-TOKEN`; each returns 200 with the **updated account object** in the same shape as
above, except delete):
| Request | Allowed for | Notes |
|---|---|---|
| `POST /api/admin/accounts/{userId}/disable` | DEMO, TEST | Ends the user's open session immediately. |
| `POST /api/admin/accounts/{userId}/enable` | DEMO, TEST | |
| `POST /api/admin/accounts/{userId}/extend` body `{ "hours": 24 }` | DEMO only | `hours` is 24 or 48. **The admin may add at most 48 hours in total per account** (`adminExtensionHours` + `hours` must not exceed 48; otherwise 409). Extends from the current expiry, or from now if already expired. Does not use up the user's own 2 extensions. |
| `DELETE /api/admin/accounts/{userId}` | DEMO only | 200 `{ "status": "deleted" }`. Permanent: the account can never sign in again. The request record is kept and that email may request a new demo. |

Errors: 400 `{ "error": "..." }` (for example extend on a TEST account, or invalid hours); 409 `{ "error": "..." }`
(already in that state); 404 as above.

## Task AD1: `static/admin/login.html`
- A centred card like `login.html`, but titled **"Fortivance Admin"** with the line "Restricted area. Authorised
  administrators only." No "Request a demo", no "Forgot password?", no link back to the public site, and no
  hint of any username.
- Form: Username, Password (with the same eye show/hide toggle as `login.html`, reset to hidden on submit),
  "Sign in" button, posting to `/admin/login` as above. `autocomplete="username"` / `"current-password"`.
- Messages from the query string (use `textContent`):
  `?error` -> "Invalid credentials.";
  `?error=locked` -> "Too many failed attempts. Try again in 15 minutes.";
  `?logout` -> "You have been signed out." (green).
- Visually distinct from the user login so the two are not confused (for example a dark navy page background
  with a white card and a small "ADMIN" label above the title).

## Task AD2: `static/admin/index.html` + `admin.js`: the admin console
Layout:
- A slim dark header: logo + "Fortivance Admin", on the right the admin's display name (from `/api/admin/me`)
  and a "Sign out" link to `/admin/logout`. No links to the public app pages.
- Summary cards from `counts`: Active, Expiring within 6 h, Expired, Disabled, Deleted.
- Toolbar: filter chips (All, Active, Expiring soon, Expired, Disabled, Not activated), a search box (email,
  username, name), a "Show deleted" checkbox (reloads with `?includeDeleted=true`), a Refresh button, and an
  "Updated <time>" label. Auto-refresh every 60 seconds.
- Table columns: Status (coloured pill), Account (username, with a small grey "Test account" tag for `TEST`),
  Name / organisation, Role, Requested, Access until (IST), Time left, Extensions (user `extensionCount of
  maxExtensions`, plus "+N h by admin" when `adminExtensionHours` > 0), Last sign-in, Actions.
- Clicking a row (or a "Details" button) expands it to show phone and purpose (purpose keeps line breaks).

Actions column (buttons; disable all of a row's buttons while one of its requests is in flight):
- **Extend**: opens a small inline menu with **24 h** and **48 h**, offering only what still fits in the 48-hour admin
  allowance (`48 - adminExtensionHours`; for example after 24 h only "24 h" remains). Show "Admin extension used
  (48 h)" instead of the button once `adminExtensionHours` is 48. Hidden for `TEST` and `DELETED` accounts.
- **Disable** / **Enable** (whichever applies; hidden for `DELETED`). Disable asks for confirmation:
  "Disable <username>? They will be signed out immediately."
- **Delete** (DEMO only, not shown for `DELETED`): a confirmation dialog that requires typing the account's
  username exactly before the Delete button enables. Text: "Delete <username>? They can never sign in with this
  account again. The request record is kept and this email may request a new demo."
- After each successful action, replace that row with the returned account (delete: reload the list) and show a
  short green toast such as "Extended jane@acmebank.com by 48 h". On an error, show the `error` text in a red
  toast. Never use `alert()`.
- Times in IST, for example "21 Nov, 6:05 PM". Time left as "15 h 20 min", "Expired" or "-".

## Task AD3: small text updates on public pages
- `forgot-password.html`: change "Owner and judge accounts are managed by the administrator." to
  "Test accounts are managed by the administrator."
- Search `static/` (excluding `static/admin/`) for any other mention of "judge" or "owner" account wording and
  list what you find; change only user-facing text.
- Do not add any link to `/admin/` anywhere outside `static/admin/`.

## Task AD4: checks
- `/admin/login` loads the admin page; the public login and About pages contain no link to it.
- Admin pages have the `noindex` meta tag and do not include `/js/api.js`.
- With the backend running: sign in as the admin, filter, search, extend, disable, enable, and delete a test
  demo account; each action updates its row and shows a toast; a 404 from any admin API returns you to
  `/admin/login`.
- No console errors on any admin page.
