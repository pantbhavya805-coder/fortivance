# Fortivance

Third-party risk intelligence and operational resilience for banks.
MVP focus: **Compliance & Dependency Intelligence** for a fictional Indian commercial bank.

> All organisations, contracts, reports and incidents in this repo are **synthetic**.

## Stack
Java 21 · Spring Boot 3.3 · MySQL 8.4 · Flyway · Docker Compose · Claude API · Cytoscape.js

## Run it

```bash
cp .env.example .env        # add your ANTHROPIC_API_KEY and DB passwords
docker compose up --build
```

Flyway runs on startup:
- `V1__init_schema.sql`: 10 core tables
- `V2__seed_synthetic_data.sql`: demo data
- `V3__workflow_ai_and_audit.sql`: workflow, users, passages, audit log, actions

Application runs on: `http://localhost:8080`
MySQL exposed on host port: `3307`

## Request a Demo

Visitors request access at `/request-demo.html`. The system creates one personal demo account (sign in with your email; a generated password is shown on screen and emailed) valid for 24 hours. Every account can switch between **Analyst** and **Approver** from the header, so one person can run the whole incident workflow. Requests are stored and each email address can request once. Access runs on synthetic demonstration data. Forgotten passwords are reset through a one-time emailed link (`/forgot-password.html`, valid 30 minutes).

Before access expires, a demo user can open `/profile.html` and extend it by 24 hours, up to two times. Passwords are stored only as bcrypt hashes; accounts become active only after the welcome email is sent. Permanent owner and judge accounts are created at startup from `OWNER_PASSWORD` and `JUDGE_PASSWORD` in `.env` (see `deploy/DEPLOY.md`).

## Security & Known Simplifications

- **Authentication**: Supports session-based Form Login (`/login.html`) and HTTP Basic authentication for curl / programmatic access.
- **API Responses**: Unauthenticated requests to `/api/**` return HTTP 401 JSON (`{"error": "Unauthorized"}`) instead of redirecting to login.
- **Authorization**: Endpoints ending in `/approve` or `/reject` require `ROLE_APPROVER` or `ROLE_ADMIN`.
- **CSRF**: Disabled for `/api/**` and `/login` (known demo simplification).

## Web Application Pages

- `/about.html`: Public landing page with operational resilience overview and live metrics.
- `/request-demo.html`: Public request form for personal 24-hour demo accounts.
- `/login.html`: Clean session login form.
- `/profile.html`: Signed-in user's demo access details, expiry countdown and extension button.
- `/admin.html`: Admin panel (owner and judge accounts only): every demo account with its expiry status, time left, extensions and current role.
- `/index.html`: Executive dashboard with 6 resilience metric cards and vendor risk registry.
- `/graph.html`: Interactive Cytoscape.js dependency graph mapping Business Services &rarr; Applications &rarr; Vendors &rarr; 4th Parties, with live vendor incident cascade simulation.
- `/vendor.html?id=`: Detailed vendor risk profile, contract validity, mis-tiering alerts, subcontractors, and dependent applications.
- `/incidents.html`: Active incident triage list.
- `/incident.html?id=`: Incident detail with live **RBI 6-Hour Reporting Window** countdown clock, monospace notice text, AI extraction, and response plan approval workflow.

## REST APIs

| Endpoint | Method | Role | Description |
|---|---|---|---|
| `/actuator/health` | GET | Public | Healthcheck probe |
| `/api/demo-requests` | POST | Public | Submit demo request and dispatch personal credentials |
| `/api/me` | GET | Authenticated | Current user details and role |
| `/api/me/demo` | GET | Authenticated | Own demo request, expiry and extension state (404 for permanent accounts) |
| `/api/me/demo/extend` | POST | Authenticated | Extend own demo access by 24 hours (max 2; 409 when reached) |
| `/api/admin/demo-accounts` | GET | Admin (`bhavya`, `judge`) | All demo requests with access state (active, expiring within 6 h, expired, not activated); no passwords |
| `/api/me/role` | POST | Authenticated | Switch own role between `ANALYST` and `APPROVER` (kept for the next login) |
| `/api/password/forgot` | POST | Public | Email a one-time reset link (same answer whether or not the account exists) |
| `/api/password/reset` | POST | Public | Set a new password with a reset token (30 minutes, single use) |
| `/api/me/password` | POST | Authenticated | Change own password (demo accounts only; needs the current password; 5 attempts per 15 min) |
| `/api/vendors` | GET | Authenticated | List all vendors (`?directOnly=true` supported) |
| `/api/vendors/{id}` | GET | Authenticated | Get vendor by ID |
| `/api/vendors/{id}/subcontractors` | GET | Authenticated | Fourth parties behind this vendor |
| `/api/vendors/{id}/profile` | GET | Authenticated | Vendor profile with docs, apps, flags & open actions |
| `/api/metrics` | GET | Authenticated | 6 operational resilience metrics |
| `/api/dependencies/graph` | GET | Authenticated | Cytoscape graph data (`?incidentVendorId=` highlights affected paths) |
| `/api/impact/vendors/{id}` | GET | Authenticated | Cascading impact report for vendor failure |
| `/api/actions` | GET | Authenticated | List remediation actions (`?incidentId=&vendorId=&status=`) |
| `/api/actions` | POST | Authenticated | Create manual action item (`source = MANUAL`) |
| `/api/actions/{id}` | PATCH | Authenticated | Update action (`closed_at` set on `DONE`) |
| `/api/audit` | GET | Authenticated | View audit logs newest first (`?entityType=&entityId=&limit=50`) |

## Demo Reset Script

To wipe incident data, event timelines, ai drafts, audit logs, and action items while preserving master data:

- **Windows (PowerShell):**
  ```powershell
  .\scripts\reset-demo.ps1
  ```
- **Linux / macOS (Bash):**
  ```bash
  ./scripts/reset-demo.sh
  ```

## Impact Query (Incident 1 Cascade)

```sql
WITH RECURSIVE affected_vendors AS (
    SELECT id FROM vendors WHERE id = 5                     -- SecureVault
    UNION ALL
    SELECT v.parent_vendor_id FROM vendors v
    JOIN affected_vendors a ON v.id = a.id
    WHERE v.parent_vendor_id IS NOT NULL
)
SELECT DISTINCT bs.code, bs.name, bs.impact_tolerance_hours,
       a.code AS app, sad.is_essential
FROM affected_vendors av
JOIN application_vendor_dependencies avd ON avd.vendor_id = av.id
JOIN applications a  ON a.id = avd.application_id
JOIN service_application_dependencies sad ON sad.application_id = a.id
JOIN business_services bs ON bs.id = sad.business_service_id
ORDER BY bs.impact_tolerance_hours;
```
