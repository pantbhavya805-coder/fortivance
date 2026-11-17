# MASTER SERVICES AGREEMENT — CLOUD INFRASTRUCTURE SERVICES

**Agreement No.:** DMB/IT/OS/2025/014
**Between:** Deccan Meridian Bank Ltd ("the Bank"), Hyderabad
**And:** NimbusCloud Infra Pvt Ltd ("the Service Provider"), Bengaluru
**Effective Date:** 1 April 2025 **Term:** 5 years

*SYNTHETIC DOCUMENT — fictional parties, created for Fortivance testing only.*

---

## 1. Scope of Services
1.1 The Service Provider shall provide Infrastructure-as-a-Service (compute, storage, managed database and network services) to host the Bank's Core Banking System, Internet Banking Portal, Mobile Banking backend, Digital Onboarding Platform and Loan Origination System, as listed in Schedule A.
1.2 Services are delivered from the Provider's Mumbai (primary) and Chennai (disaster recovery) regions.

## 2. Service Levels
2.1 Monthly availability for production workloads: 99.95%.
2.2 Service credits as per Schedule B. Service credits do not limit the Bank's other rights under this Agreement.

## 3. Data Location and Protection
3.1 All Bank data, including backups and logs, shall be stored and processed only within India.
3.2 Customer data shall be encrypted at rest (AES-256) and in transit (TLS 1.2 or higher). Encryption keys for production databases shall be held in the Bank-controlled key management service.
3.3 On termination, the Provider shall return all Bank data in an agreed format within 30 days and securely delete all copies within 60 days, providing a certificate of destruction.

## 4. Information Security
4.1 The Provider shall maintain an information security programme aligned to ISO/IEC 27001 and shall maintain a valid ISO/IEC 27001 certification for the in-scope regions throughout the Term.
4.2 The Provider shall furnish a SOC 2 Type II report annually, within 90 days of the end of each reporting period, and a bridge letter on request for any gap period.

## 5. Incident Notification
5.1 The Provider shall notify the Bank of any Security Incident or service disruption affecting the Bank's workloads **within 6 hours of detection**.
5.2 Notifications shall be sent to the Bank's Chief Information Security Officer at **ciso-office@deccanmeridian.example** and to the Bank's Security Operations Centre hotline listed in Schedule D.
5.3 Initial notification shall include: nature of the incident, time of detection, affected services, and immediate containment steps. Updates shall follow every 12 hours until resolution, and a root-cause analysis report within 10 business days of closure.
5.4 This obligation extends to incidents at any Subcontractor that affect the Bank's workloads.

## 6. Subcontracting
6.1 The Provider shall not subcontract any material part of the Services without the Bank's **prior written consent**.
6.2 Approved Subcontractors as at the Effective Date are listed in Schedule C. This includes **SecureVault Datacentres Pvt Ltd** (colocation and physical facilities, Mumbai region).
6.3 The Provider shall ensure that Subcontractors are bound by obligations no less stringent than those in this Agreement, including confidentiality, audit, data location and incident notification. The Provider remains fully liable for Subcontractor performance.

## 7. Audit and Inspection Rights
7.1 The Bank, its internal and external auditors, and the **Reserve Bank of India or any person authorised by it**, shall have the right to access, audit and inspect the Provider's (and its Subcontractors') premises, systems, books and records relating to the Services, on reasonable notice.
7.2 The Provider shall provide all information reasonably requested by the Bank to meet its regulatory obligations.

## 8. Business Continuity and Disaster Recovery
8.1 The Provider shall maintain a documented BCP/DR plan covering the Services, with an RTO of 4 hours and RPO of 15 minutes for Critical workloads.
8.2 DR shall be tested at least annually with the Bank's participation. Test results shall be shared with the Bank within 30 days of the test.

## 9. Termination and Exit
9.1 Either party may terminate for material breach with 90 days' notice and opportunity to cure.
9.2 The Bank may terminate where directed by a regulator.
9.3 On expiry or termination, the Provider shall provide **transition assistance for up to 180 days** at the rates in Schedule B to enable orderly migration to the Bank or a successor provider, without interruption to Services.

## 10. Governing Law
This Agreement is governed by the laws of India, with exclusive jurisdiction of the courts at Hyderabad.

---
**Schedule C — Approved Subcontractors**
| Subcontractor | Service | Location |
|---|---|---|
| SecureVault Datacentres Pvt Ltd | Colocation, power, cooling, physical security | Mumbai |
