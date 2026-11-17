# SOC 2 TYPE II REPORT — SUMMARY EXTRACT

**Service Organisation:** NimbusCloud Infra Pvt Ltd
**System:** NimbusCloud IaaS Platform (Mumbai and Chennai regions)
**Trust Services Criteria in scope:** Security, Availability, Confidentiality
**Review Period:** 1 April 2025 – 31 March 2026
**Independent Service Auditor:** Ashby & Rao LLP, Chartered Accountants (fictional)
**Report Date:** 20 June 2026

*SYNTHETIC DOCUMENT — fictional parties, created for Fortivance testing only.*

---

## Section 1 — Independent Service Auditor's Opinion
In our opinion, in all material respects, the description fairly presents the NimbusCloud IaaS Platform as designed and implemented throughout the period 1 April 2025 to 31 March 2026, and the controls stated in the description were suitably designed and operated effectively to provide reasonable assurance that the service commitments and system requirements were achieved, based on the applicable trust services criteria, **except for the matter described in Section 4 (Exception CC6.2-01)**.

## Section 2 — Subservice Organisations (Carve-out Method)
NimbusCloud uses **SecureVault Datacentres Pvt Ltd** for colocation services in the Mumbai region, including physical access security, power, cooling and environmental protection.

The description **excludes** the controls of SecureVault Datacentres Pvt Ltd. This report does not provide assurance over physical and environmental controls at the Mumbai facility. User entities should obtain and evaluate assurance over SecureVault's controls separately.

## Section 3 — Complementary User Entity Controls (CUECs)
User entities are expected to implement the following controls:
1. Manage their own user accounts and access rights to the NimbusCloud console.
2. Review the NimbusCloud access logs made available to them at least quarterly.
3. Configure backup schedules and retention for their own workloads.
4. Manage encryption keys held in user-controlled key management services.
5. Notify NimbusCloud promptly of any suspected security incident involving their account.

## Section 4 — Testing Exceptions
| Ref | Criterion | Control | Test performed | Exception noted |
|---|---|---|---|---|
| CC6.2-01 | CC6.2 — Logical access removal | Access for terminated employees is revoked within 24 hours | Sampled 40 terminations during the period | **3 of 40** terminated employees retained console access for 4–11 days after termination. No evidence of misuse was identified. |

**Management response:** NimbusCloud has implemented an automated HR-to-identity deprovisioning integration effective 1 May 2026.

## Section 5 — Other Information
- ISO/IEC 27001:2022 certificate held by NimbusCloud, valid until 30 September 2027.
- Last DR test of the Chennai region: 14 February 2026 — completed within the 4-hour RTO.
