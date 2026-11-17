# Fortivance

Third-party risk intelligence and operational resilience for banks.
MVP focus: **Compliance & Dependency Intelligence** for a fictional Indian commercial bank.

> All organisations, contracts, reports and incidents in this repo are **synthetic**.

## Stack
Java 21 · Spring Boot 3.3 · MySQL 8.4 · Flyway · Docker Compose · Claude API

## Run it

```bash
cp .env.example .env        # add your ANTHROPIC_API_KEY and DB passwords
docker compose up --build
```

Flyway runs on startup:
- `V1__init_schema.sql`: 10 tables
- `V2__seed_synthetic_data.sql`: demo data

Check it:
```bash
curl localhost:8080/actuator/health
curl localhost:8080/api/vendors
curl localhost:8080/api/vendors/1/subcontractors   # -> SecureVault (fourth party)
```

To run the app from your IDE instead, start MySQL only with `docker compose up mysql`, then run `FortivanceApplication`.

## Data model

```
business_services ──< service_application_dependencies >── applications
applications ──< application_vendor_dependencies >── vendors
vendors.parent_vendor_id ──> vendors         (fourth parties)
vendors ──< documents ──< evidence_mappings >── control_requirements
vendors ──< incident_notices
```

| # | Table | Purpose |
|---|---|---|
| 1 | banks | Regulated entity |
| 2 | vendors | Direct vendors and subcontractors (`parent_vendor_id`) |
| 3 | applications | Bank IT systems |
| 4 | business_services | Customer-facing services with impact tolerances |
| 5 | application_vendor_dependencies | App depends on vendor |
| 6 | service_application_dependencies | Service depends on app |
| 7 | documents | Contracts, SOC 2, notices (+ extracted text) |
| 8 | control_requirements | Obligations to test evidence against |
| 9 | evidence_mappings | AI-proposed, human-reviewed requirement-to-evidence links |
| 10 | incident_notices | Vendor incident notices (incident vendor vs reporting vendor) |

## Synthetic dataset

**Deccan Meridian Bank Ltd**, a fictional commercial bank headquartered in Hyderabad.

| Vendor | Type | Parent |
|---|---|---|
| NimbusCloud Infra | Cloud hosting, CRITICAL | — |
| PayRail Switching Services | UPI and card processing, CRITICAL | — |
| VeriKYC Technologies | eKYC / video KYC, HIGH | — |
| NotifyHub Communications | SMS / OTP, HIGH | — |
| SecureVault Datacentres | Colocation | NimbusCloud |
| TextLink Telecom Gateway | SMS aggregator | NotifyHub |

**Applications (8):** CBS, IBP, MBB, UPI, CMS, DOP, LOS, CNE
**Services (6):** Accounts, Digital Payments, Cards, Onboarding, Lending, Alerts & OTP

**Documents in `sample-docs/`**
- `contracts/nimbuscloud-msa.md`: a **complete** contract (6-hour notice, RBI audit rights, subcontractor consent, DR, 180-day exit).
- `contracts/notifyhub-service-agreement.md`: a contract **with gaps** (no notice timeline or recipient, subcontracting at the vendor's discretion, no regulator access, vague BCP, 30-day exit with no transition support).
- `audit-reports/nimbuscloud-soc2-type2-fy2026.md`: **carves out SecureVault** and has one access-control exception.
- `incident-notices/` ×3: a SecureVault cooling failure (cascades through NimbusCloud), TextLink SMS delays (reported late, after resolution), and suspected PayRail UAT ransomware.

**Planted signals the AI should find:** an overdue PayRail review; NotifyHub likely mis-tiered as non-material; the undisclosed TextLink subcontractor; the SOC 2 carve-out leaving SecureVault without assurance; the late NotifyHub notice.

`control_requirements` holds a 13-control starter set grouped by RBI outsourcing theme. **`source_reference` = 'TO VERIFY'** means the control must be checked against the official RBI text before you cite paragraph numbers. The set expands to 100 controls later.

## Impact query (incident 1 cascade)

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
