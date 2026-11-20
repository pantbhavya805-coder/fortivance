# Fortivance

Third-party risk intelligence and operational resilience for Indian banks.

> All organisations, contracts, reports and incidents in this repo are **synthetic**. The demo runs on a
> fictional bank, Deccan Meridian Bank, and its invented vendors.

## What it is

Banks run their critical services on vendors (cloud hosts, payment processors, KYC and messaging providers) and on
those vendors' own subcontractors. When a vendor has a cyber incident, the bank has hours, not days, to work out
which of its services are hit and to report to RBI (the clock starts when the bank learns of the incident).
Today that work is mostly done by hand: reading the vendor's email, searching spreadsheets for what depends on the
vendor, and checking audit reports page by page. Fortivance does that work in minutes and leaves every decision
with a person.

## What you can do with it

| Use | What happens | Page |
|---|---|---|
| **Handle a vendor incident** | Upload the vendor's incident notice (PDF or text). Claude extracts the facts, Fortivance matches the vendor, walks the dependency graph to the affected business services, starts the 6-hour reporting clock and drafts a response plan. An Approver approves or rejects it; on approval the actions are created and Claude drafts an escalation note for leadership. Every step goes to the audit log. | `/incidents.html` |
| **Review vendor evidence** | Claude reads a vendor's contract and SOC 2 report and finds the passage that meets each requirement the bank takes from the RBI Directions. The gap report shows each requirement as found, partial or missing with the cited passage and a rule-based risk score; an analyst verifies or rejects every finding. | `/evidence.html` |
| **See dependencies** | An interactive graph from business services to systems, vendors and fourth parties, with a simulation of what a vendor outage reaches. | `/graph.html` |
| **Track resilience** | A dashboard of six resilience metrics, a vendor register with tiers and contract dates, vendor profiles with mis-tiering alerts, and remediation actions. | `/index.html`, `/vendor.html` |
| **Load a bank's own register** | Import vendors, systems and services from three CSV templates, checked row by row before anything is saved. | `/import.html` |

New to the banking terms? Read the glossary below, or the in-app **Guide** at `/guide.html`. Judges: see
`/judges.html` for the demo path and how the app meets the hackathon checklist.

## Glossary: the demo setting and its terms

All data in the demo is synthetic: the bank, vendors, contracts, plans and incidents are invented.

Inside the app, the same explanations are in the public **Guide** (`/guide.html`): the setting, an incident walkthrough, the evidence walkthrough, the strategy and an A-Z glossary. Banking terms on the incident and evidence screens are underlined; hovering or tapping one shows its definition (`js/glossary.js` holds the single list of definitions), and a **Guide for this page** button opens the matching guide section in a side panel.

### The setting
**Deccan Meridian Bank Ltd (DMB)** is a fictional commercial bank headquartered in Hyderabad. It depends on outside
vendors (third parties), and some of those vendors depend on their own suppliers (fourth parties, or subcontractors).

| Vendor | Service to the bank | Risk tier | Notes |
|---|---|---|---|
| NimbusCloud Infra Pvt Ltd | Cloud hosting (IaaS) for core banking, internet and mobile banking, onboarding and lending | CRITICAL | Hosts most critical systems; holds financial data |
| PayRail Switching Services Pvt Ltd | UPI switching and card transaction processing | CRITICAL | Periodic review overdue (deliberate demo signal) |
| VeriKYC Technologies Pvt Ltd | Video KYC, Aadhaar eKYC and document verification | HIGH | Holds customer personal data; its contract has deliberate gaps |
| NotifyHub Communications Pvt Ltd | SMS, email and push delivery, including OTPs and alerts | HIGH | SMS gateway, so outside the RBI IT outsourcing provisions (see Out of scope) |
| SecureVault Datacentres Pvt Ltd | Data centre (power, cooling, security) for NimbusCloud Mumbai | CRITICAL | Subcontractor of NimbusCloud (fourth party) |
| TextLink Telecom Gateway Pvt Ltd | Upstream SMS aggregation | HIGH | Subcontractor of NotifyHub, not disclosed in its contract |

### General terms
| Term | Meaning |
|---|---|
| TPRM | Third-Party Risk Management: managing the risk of relying on outside vendors (contracts, assurance, reviews, incidents). |
| Operational resilience | The bank's ability to keep critical services running, or restore them within tolerance, when something fails. |
| Third party / fourth party | A vendor the bank contracts with directly / a vendor's own subcontractor. |
| RBI | Reserve Bank of India, the banking regulator. |
| RBI Outsourcing Directions 2025 | RBI (Commercial Banks - Managing Risks in Outsourcing) Directions, 2025: the source of most requirements below. Paragraph references marked "(verify)" must be checked against the official text before quoting. |
| SOC 2 Type II | An independent auditor's report on a vendor's controls over a period of time. A "carve-out" means it excludes some subservice organisations (such as the vendor's data-centre provider). |
| KYC / eKYC | Know Your Customer: identity verification of customers, here done by VeriKYC. |
| OTP | One-time password sent by SMS to confirm logins and payments. |
| UPI / IMPS | India's instant payment systems (Unified Payments Interface / Immediate Payment Service). |

### Systems (applications) and business services
Failures travel along this chain: **vendor → application → business service**.

| Code | Application | What it does |
|---|---|---|
| CBS | Core Banking System | Accounts, deposits, general ledger, customer master |
| IBP | Internet Banking Portal | Retail and SME internet banking |
| MBB | Mobile Banking Backend | APIs behind the DMB mobile app |
| UPI | UPI Switch Integration | Bank-side link to the UPI network |
| CMS | Card Management System | Debit/credit card issuance and authorisation |
| DOP | Digital Onboarding Platform | Account opening with eKYC and video KYC |
| LOS | Loan Origination System | Loan applications and underwriting |
| CNE | Customer Notification Engine | Sends OTPs, alerts and statements |

| Code | Business service | Impact tolerance | Continuity plan |
|---|---|---|---|
| SVC-ALERT | Customer Alerts & OTP | 1 h | BCP-06 |
| SVC-PAY | Digital Payments (UPI/IMPS) | 2 h | BCP-02 |
| SVC-CARD | Card Payments | 2 h | BCP-03 |
| SVC-ACCT | Retail Accounts & Deposits | 4 h | BCP-01 |
| SVC-ONB | Customer Onboarding | 24 h | BCP-04 |
| SVC-LEND | Retail Lending | 24 h | BCP-05 |

| Term | Meaning |
|---|---|
| Impact tolerance | The longest a business service can be disrupted before it causes harm the bank will not accept. Set by the bank per service. |
| DISRUPTED | An application the service cannot work without (an *essential* dependency) is affected: the service is effectively down. |
| DEGRADED | Only a non-essential application is affected: the service still works, but worse. These are the only two impact levels. |
| Exposed | Any service reached by the incident, disrupted or degraded ("2 disrupted / 4 exposed"). |
| BCP-01 to BCP-06 | Business Continuity Plans: pre-written playbooks (for example switching to a backup provider) for each business service. |
| RTO / RPO | Recovery Time Objective (how fast a system must be restored) / Recovery Point Objective (how much data loss, in minutes, is acceptable). |
| DR | Disaster recovery (for example NimbusCloud's Chennai DR region). |

### Incidents
| Term | Meaning |
|---|---|
| Incident notice | A vendor's message telling the bank something went wrong. Sample notices, a live-demo notice and a prompt-injection test are in `sample-docs/` (its README says what each one shows). |
| Reference numbers | The vendor's own ticket number, kept so analysts can quote it: **NH**-INC-2026-0412 (NotifyHub, SMS delays), INC-**NC**-2026-1103 (NimbusCloud, data-centre cooling at SecureVault), **PR-SEC**-2026-0027 (PayRail, suspected ransomware). |
| RBI 6-hour reporting window | Banks are expected to report significant cyber incidents to RBI within 6 hours of detection. The clock counts from the incident's detected time. |
| Window elapsed | The 6 hours have passed (always the case for the older demo incidents). Fortivance shows the clock but never decides reportability; an analyst does. |
| Analyst / Approver | The two roles: the analyst triages and drafts; the approver approves or rejects the AI-drafted response plan. |

### Vendor evidence and gap reports
| Term | Meaning |
|---|---|
| Requirement (VS-…) | A control the bank expects from a vendor, taken from the RBI Directions or internal policy. Five are used in the demo (below). |
| Evidence | A passage in the vendor's own documents (contract, SOC 2 report) that shows a requirement is met. |
| Gap report | For one vendor: each requirement, whether evidence was found, the exact cited passage, the human review status and a risk score. |
| FOUND / PARTIAL / MISSING | The document fully meets / partly meets / does not meet the requirement. NOT ASSESSED means no document was analysed for it. |
| NEEDS REVIEW / VERIFIED / REJECTED | Human review of the AI finding: not yet reviewed / confirmed by an analyst / overruled by an analyst. |
| Coverage % | Requirements with FOUND evidence, out of all five. "Verified" counts only those a human has confirmed. |
| Out of scope | The vendor is excluded from the IT outsourcing requirements (NotifyHub as an SMS gateway, RBI Directions 2025 para 5(2)(iii)), so no gap analysis is run, but it is still monitored for incidents and continuity. |
| Residual risk score | A 0-100 rule-based score (no AI) built from listed factors; 70+ HIGH, 40-69 MEDIUM, below 40 LOW. |
| Inherent tier | How critical the vendor is before any controls: CRITICAL 40 points, HIGH 30, MEDIUM 20, LOW 10. |
| Data access | What data the vendor handles: FINANCIAL 15 points, CUSTOMER_PII 10, INTERNAL 5, NONE 0. |
| Other score factors | Missing evidence 8 points each (max 30); partial evidence 4 each (max 16); periodic review overdue 10; open incidents in the vendor's chain 5 each (max 10); subcontractors with no assurance report on file 5 each. |
| Evidence pack | An export of the gap report with citations, for auditors or the board. |

| Code | Requirement | Source |
|---|---|---|
| VS-CON-01 | Audit and regulator access rights: the bank, its auditors and RBI may inspect the vendor and its subcontractors | RBI Directions, para 69(xii) |
| VS-CON-02 | Incident notification obligation: the vendor must report incidents to the bank with a set timeline and recipient | RBI Directions, para 56; 69(iii) |
| VS-CON-03 | Control over subcontracting: material subcontracting needs the bank's prior approval, and obligations flow down | RBI Directions, para 4(2); 69(i), (xiii) |
| VS-BCP-01 | Tested BCP/DR: the vendor has a continuity and DR plan, tested periodically, with results shared | RBI Directions, para 78 (applying 40-43) |
| VS-SEC-02 | Subservice organisations covered: where a SOC 2 report carves out a subcontractor, the bank gets separate assurance | Internal policy |

## Loading a bank's own register

Each bank runs its own Fortivance instance (RBI's outsourcing rules make a shared multi-bank platform hard to
justify: the bank must keep audit rights, data separation and Indian data residency for any tool it outsources to).
The register is loaded at `/import.html` from three CSV templates (`static/templates/`): vendors (with
subcontractors), systems (with the vendors each depends on) and business services (with essential and supporting
systems). A check run validates every row and reports problems by file and line without saving; the import then
saves everything in one transaction, never deletes, and is audit-logged. It needs the Approver role. Updating
existing records is allowed only with `IMPORT_UPDATE_EXISTING=true` (off by default, so the shared demo is protected).

## Stack
Java 21 · Spring Boot 3.3 · MySQL 8.4 · Flyway · Docker Compose · Claude API · Cytoscape.js

## Run it locally

**You need:** Docker Desktop (with Docker Compose), and an Anthropic API key for the AI steps. Java 21 and Maven
are only needed to run the tests outside Docker.

1. **Get the code**
   ```bash
   git clone <this repo>
   cd fortivance
   ```
2. **Create your settings file.** Copy the example and fill it in. The real `.env` is git-ignored; never commit it.
   ```bash
   cp .env.example .env          # PowerShell: Copy-Item .env.example .env
   ```
   | Variable | Needed for |
   |---|---|
   | `ANTHROPIC_API_KEY` | Incident extraction, response plans and evidence mapping (without it the rest of the app still works) |
   | `JUDGE_PASSWORD` | Creates the `judge` account you sign in with locally |
   | `OWNER_PASSWORD` | Creates the admin account for the admin console (optional) |
   | `DB_PASSWORD`, `DB_ROOT_PASSWORD` | MySQL passwords (defaults work for local use) |
   | `MAIL_USERNAME`, `MAIL_PASSWORD`, `CONTACT_TO` | Contact form, demo welcome and password reset emails (optional locally; without them, use the `judge` account) |
3. **Start it**
   ```bash
   docker compose up --build
   ```
   The first start builds the app and runs the Flyway migrations `V1` to `V9` (schema, synthetic seed data,
   workflow, demo requests, admin console, demo limits).
4. **Open** `http://localhost:8080`. It opens on the About page; choose **Log in** and sign in as `judge` with
   your `JUDGE_PASSWORD`. Switch between Analyst and Approver in the header.
5. **Try an incident.** On Incidents, upload `sample-docs/incident-notices/live-demo/PR-SEC-2026-0031.md`
   (or any notice in `sample-docs/incident-notices/`), then open it to see the clock, impact and plan.

MySQL is exposed on host port `3307`. To start again from clean data, run the reset script (below). To stop:
`docker compose down` (add `-v` to delete the database too).

**Run the tests** (no database needed):
```bash
mvn test
```

## Request a Demo

Visitors request access at `/request-demo.html`. The system creates one personal demo account (sign in with your email and a password the visitor chooses on the form: 8 to 64 characters with upper and lower case, a number and a symbol; it is stored only as a bcrypt hash and never emailed) valid for 24 hours. Every account can switch between **Analyst** and **Approver** from the header, so one person can run the whole incident workflow. Requests are stored and each email address can request once. Access runs on synthetic demonstration data. Forgotten passwords are reset through a one-time emailed link (`/forgot-password.html`, valid 30 minutes).

Before access expires, a demo user can open `/profile.html` and extend it by 24 hours, up to two times. Passwords are stored only as bcrypt hashes; accounts become active only after the welcome email is sent. Permanent owner and judge accounts are created at startup from `OWNER_PASSWORD` and `JUDGE_PASSWORD` in `.env` (see `deploy/DEPLOY.md`).

**Accounts.** `judge` is a permanent test account for judges (Analyst/Approver switch, no expiry); its password comes from `JUDGE_PASSWORD` and is shared privately. The administrator account signs in only through a separate, unlinked admin console (address shared privately), where it can view every account, disable or enable it, delete a demo account, and add up to 48 hours of access to a demo account. Admin accounts cannot sign in to the application, the admin area answers 404 to everyone else, sign-in locks for 15 minutes after 5 failures, and admin actions require a CSRF token.

## Security & Known Simplifications

- **Authentication**: Supports session-based Form Login (`/login.html`) and HTTP Basic authentication for curl / programmatic access.
- **API Responses**: Unauthenticated requests to `/api/**` return HTTP 401 JSON (`{"error": "Unauthorized"}`) instead of redirecting to login.
- **Authorization**: Endpoints ending in `/approve` or `/reject` require `ROLE_APPROVER` or `ROLE_ADMIN`.
- **CSRF**: Disabled for `/api/**` and `/login` (known demo simplification).

## Web Application Pages

- `/about.html`: Public landing page: the problem, why this idea, how close the demo is to a real bank, live metrics and contact form.
- `/guide.html`: Public help guide: the setting, incident and evidence walkthroughs and an A-Z glossary.
- `/judges.html`: Public judge guide: demo path, checklist and rubric mapping, where Claude and the mailbox are used.
- `/request-demo.html`: Public request form for personal 24-hour demo accounts.
- `/login.html`: Sign-in form (`/login` redirects here). Logging out returns to the About page.
- `/profile.html`: Signed-in user's demo access details, expiry countdown and extension button.
- `/index.html`: Executive dashboard with 6 resilience metric cards and vendor risk registry.
- `/graph.html`: Interactive Cytoscape.js dependency graph mapping Business Services &rarr; Applications &rarr; Vendors &rarr; 4th Parties, with live vendor incident cascade simulation.
- `/vendor.html?id=`: Detailed vendor risk profile, contract validity, mis-tiering alerts, subcontractors, and dependent applications.
- `/incidents.html`: Incident list and notice upload.
- `/incident.html?id=`: Incident detail with live **RBI 6-Hour Reporting Window** countdown clock, notice text, AI extraction, and response plan approval workflow.
- `/evidence.html`, `/evidence-pack.html`: Vendor evidence, gap reports, risk score and verify/dispute.
- `/import.html`: Vendor register import from CSV templates (check, then import as Approver).
- `/eval.html`: Accuracy of the stored AI outputs against hand-labelled answers (`docs/eval/gold.json`).

## REST APIs

| Endpoint | Method | Role | Description |
|---|---|---|---|
| `/actuator/health` | GET | Public | Healthcheck probe |
| `/api/contact` | POST | Public | Contact form on the About page: forwards to admin@fortivance.in (Reply-To visitor) and sends an automatic acknowledgement |
| `/api/contact/topics` | GET | Public | Topic options for the contact form |
| `/api/demo-requests` | POST | Public | Submit demo request and dispatch personal credentials |
| `/api/me` | GET | Authenticated | Current user details and role |
| `/api/me/demo` | GET | Authenticated | Own demo request, expiry and extension state (404 for permanent accounts) |
| `/api/me/demo/extend` | POST | Authenticated | Extend own demo access by 24 hours (max 2; 409 when reached) |
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
