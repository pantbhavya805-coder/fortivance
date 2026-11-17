-- Synthetic demo data. All organisations are fictional.
-- Explicit IDs keep cross-references readable and the demo repeatable.

-- ---------------------------------------------------------------- bank
INSERT INTO banks (id, name, short_code, bank_type, headquarters) VALUES
(1, 'Deccan Meridian Bank Ltd', 'DMB', 'COMMERCIAL', 'Hyderabad');

-- ---------------------------------------------------------------- vendors
-- 4 direct vendors (parent NULL) + 2 fourth parties (parent set)
INSERT INTO vendors (id, bank_id, parent_vendor_id, name, service_category, service_description,
                     risk_tier, is_material, data_access, contract_start, contract_end,
                     last_assessed_on, next_review_due, status) VALUES
(1, 1, NULL, 'NimbusCloud Infra Pvt Ltd', 'CLOUD_HOSTING',
    'IaaS hosting for core banking, internet and mobile banking, onboarding and lending platforms',
    'CRITICAL', TRUE,  'FINANCIAL',    '2025-04-01', '2030-03-31', '2026-07-15', '2027-01-15', 'ACTIVE'),
(2, 1, NULL, 'PayRail Switching Services Pvt Ltd', 'PAYMENTS',
    'UPI switching and card transaction processing',
    'CRITICAL', TRUE,  'FINANCIAL',    '2023-10-01', '2028-09-30', '2026-03-20', '2026-09-20', 'ACTIVE'),
(3, 1, NULL, 'VeriKYC Technologies Pvt Ltd', 'IDENTITY_VERIFICATION',
    'Video KYC, Aadhaar-based eKYC and document verification APIs',
    'HIGH',     TRUE,  'CUSTOMER_PII', '2024-02-01', '2027-01-31', '2025-12-10', '2026-12-10', 'ACTIVE'),
(4, 1, NULL, 'NotifyHub Communications Pvt Ltd', 'MESSAGING',
    'SMS, email and push delivery including OTPs and transaction alerts',
    'HIGH',     FALSE, 'CUSTOMER_PII', '2024-07-15', '2026-07-14', '2024-07-01', '2025-07-01', 'ACTIVE'),
(5, 1, 1,    'SecureVault Datacentres Pvt Ltd', 'DATA_CENTRE',
    'Colocation, power, cooling and physical security for NimbusCloud Mumbai region (subcontractor of NimbusCloud)',
    'CRITICAL', FALSE, 'NONE',         NULL, NULL, NULL, NULL, 'ACTIVE'),
(6, 1, 4,    'TextLink Telecom Gateway Pvt Ltd', 'SMS_AGGREGATOR',
    'Upstream SMS aggregation used by NotifyHub (subcontractor, not disclosed in contract)',
    'HIGH',     FALSE, 'CUSTOMER_PII', NULL, NULL, NULL, NULL, 'ACTIVE');
-- Deliberate demo signals:
--  * PayRail next_review_due (2026-09-20) is overdue.
--  * NotifyHub: contract_end passed but auto-renewed, last assessed 2024, marked non-material
--    despite carrying OTPs for payments -> likely mis-tiered.

-- ---------------------------------------------------------------- applications
INSERT INTO applications (id, bank_id, name, code, description, criticality, owner_team, rto_hours, rpo_minutes) VALUES
(1, 1, 'Core Banking System',          'CBS', 'Accounts, deposits, GL and customer master',        'CRITICAL', 'Core Banking IT',   2, 15),
(2, 1, 'Internet Banking Portal',      'IBP', 'Retail and SME internet banking',                   'CRITICAL', 'Digital Channels',  4, 15),
(3, 1, 'Mobile Banking Backend',       'MBB', 'APIs serving the DMB mobile app',                   'CRITICAL', 'Digital Channels',  4, 15),
(4, 1, 'UPI Switch Integration',       'UPI', 'Bank-side UPI integration with the payment switch', 'CRITICAL', 'Payments IT',       1, 5),
(5, 1, 'Card Management System',       'CMS', 'Debit/credit card issuance and authorisation',      'CRITICAL', 'Payments IT',       2, 5),
(6, 1, 'Digital Onboarding Platform',  'DOP', 'Account opening with eKYC and video KYC',           'HIGH',     'Digital Channels',  8, 60),
(7, 1, 'Loan Origination System',      'LOS', 'Retail loan application and underwriting workflow', 'HIGH',    'Lending IT',        8, 60),
(8, 1, 'Customer Notification Engine', 'CNE', 'Routes OTPs, alerts and statements to customers',   'CRITICAL', 'Digital Channels',  1, 5);

-- ---------------------------------------------------------------- business services
INSERT INTO business_services (id, bank_id, name, code, description, is_customer_facing, criticality,
                               impact_tolerance_hours, bcp_plan_ref, business_owner) VALUES
(1, 1, 'Retail Accounts & Deposits',  'SVC-ACCT',  'Balance enquiry, transfers, deposits',          TRUE, 'CRITICAL', 4,  'BCP-01', 'Head of Retail Banking'),
(2, 1, 'Digital Payments (UPI/IMPS)', 'SVC-PAY',   'Customer UPI and IMPS payments',                TRUE, 'CRITICAL', 2,  'BCP-02', 'Head of Payments'),
(3, 1, 'Card Payments',               'SVC-CARD',  'Debit and credit card transactions',            TRUE, 'CRITICAL', 2,  'BCP-03', 'Head of Cards'),
(4, 1, 'Customer Onboarding',         'SVC-ONB',   'New-to-bank digital account opening',           TRUE, 'HIGH',     24, 'BCP-04', 'Head of Digital'),
(5, 1, 'Retail Lending',              'SVC-LEND',  'Personal and home loan origination',            TRUE, 'HIGH',     24, 'BCP-05', 'Head of Retail Lending'),
(6, 1, 'Customer Alerts & OTP',       'SVC-ALERT', 'Transaction alerts and OTP authentication',     TRUE, 'CRITICAL', 1,  'BCP-06', 'Head of Digital');

-- ---------------------------------------------------------------- app -> vendor
INSERT INTO application_vendor_dependencies (application_id, vendor_id, dependency_type, is_single_point, alternative_vendor, notes) VALUES
(1, 1, 'HOSTING',    FALSE, 'NimbusCloud Chennai DR region', 'Primary in MUM-1'),
(2, 1, 'HOSTING',    FALSE, 'NimbusCloud Chennai DR region', NULL),
(3, 1, 'HOSTING',    FALSE, 'NimbusCloud Chennai DR region', NULL),
(3, 4, 'MESSAGING',  TRUE,  NULL,                            'Login OTPs for the mobile app'),
(4, 2, 'PROCESSING', TRUE,  NULL,                            'No secondary UPI switch contracted'),
(5, 2, 'PROCESSING', TRUE,  NULL,                            NULL),
(6, 1, 'HOSTING',    TRUE,  NULL,                            'DR not yet configured for DOP'),
(6, 3, 'API',        TRUE,  NULL,                            'eKYC and video KYC'),
(7, 1, 'HOSTING',    TRUE,  NULL,                            'DR not yet configured for LOS'),
(7, 3, 'API',        FALSE, 'Manual KYC at branch',          NULL),
(8, 4, 'MESSAGING',  TRUE,  NULL,                            'Sole SMS/OTP provider');

-- ---------------------------------------------------------------- service -> app
INSERT INTO service_application_dependencies (business_service_id, application_id, is_essential) VALUES
(1, 1, TRUE), (1, 2, FALSE), (1, 3, FALSE),
(2, 4, TRUE), (2, 1, TRUE), (2, 3, FALSE), (2, 8, TRUE),
(3, 5, TRUE), (3, 1, TRUE), (3, 8, FALSE),
(4, 6, TRUE), (4, 1, TRUE),
(5, 7, TRUE), (5, 1, TRUE),
(6, 8, TRUE), (6, 3, FALSE);

-- ---------------------------------------------------------------- documents
INSERT INTO documents (id, vendor_id, doc_type, title, file_path, issued_on, valid_until, processing_status) VALUES
(1, 1, 'CONTRACT',        'NimbusCloud Master Services Agreement DMB/IT/OS/2025/014', 'contracts/nimbuscloud-msa.md',                    '2025-04-01', '2030-03-31', 'UPLOADED'),
(2, 4, 'CONTRACT',        'NotifyHub Service Agreement NH/ENT/2024/0387',              'contracts/notifyhub-service-agreement.md',        '2024-07-15', NULL,         'UPLOADED'),
(3, 1, 'SOC2_REPORT',     'NimbusCloud SOC 2 Type II (Apr 2025 - Mar 2026)',           'audit-reports/nimbuscloud-soc2-type2-fy2026.md',  '2026-06-20', '2027-03-31', 'UPLOADED'),
(4, 1, 'INCIDENT_NOTICE', 'NimbusCloud notice INC-NC-2026-1103 (SecureVault cooling)', 'incident-notices/INC-NC-2026-1103.md',            '2026-11-03', NULL,         'UPLOADED'),
(5, 4, 'INCIDENT_NOTICE', 'NotifyHub notice NH-INC-2026-0412 (SMS delays)',            'incident-notices/NH-INC-2026-0412.md',            '2026-10-09', NULL,         'UPLOADED'),
(6, 2, 'INCIDENT_NOTICE', 'PayRail notice PR-SEC-2026-0027 (suspected ransomware)',    'incident-notices/PR-SEC-2026-0027.md',            '2026-11-14', NULL,         'UPLOADED');

-- ---------------------------------------------------------------- incident notices
INSERT INTO incident_notices (id, vendor_id, reported_by_vendor_id, document_id, reference_no, incident_type,
                              severity_reported, summary, detected_at, notified_at, resolved_at, status) VALUES
(1, 5, 1, 4, 'INC-NC-2026-1103', 'DEGRADATION', 'HIGH',
    'Cooling failure at SecureVault Mumbai facility; ~30% of NimbusCloud MUM-1 hosts throttled or powered down.',
    '2026-11-03 12:40:00', '2026-11-03 16:05:00', NULL, 'RECEIVED'),
(2, 6, 4, 5, 'NH-INC-2026-0412', 'DEGRADATION', 'MEDIUM',
    'Capacity issue at TextLink aggregator delayed or dropped SMS including OTPs for ~5 hours.',
    '2026-10-09 09:10:00', '2026-10-09 19:45:00', '2026-10-09 14:30:00', 'RECEIVED'),
(3, 2, 2, 6, 'PR-SEC-2026-0027', 'RANSOMWARE', 'HIGH',
    'Suspected ransomware in PayRail UAT environment; production reported unaffected (preliminary).',
    '2026-11-14 02:30:00', '2026-11-14 05:10:00', NULL, 'RECEIVED');
-- Demo signals: notice 1 arrived 3h25m after detection (inside the 6h contract term);
-- notice 2 arrived ~10.5h after detection and only after resolution, with no contractual timeline to test against.

-- ---------------------------------------------------------------- control requirements (starter set)
-- Themes follow the RBI outsourcing requirements in the project brief.
-- source_reference must be checked against the official RBI text before any external use.
INSERT INTO control_requirements (code, theme, title, requirement_text, source_framework, source_reference, applies_to_tiers, expected_evidence) VALUES
('VS-INV-01', 'INVENTORY', 'Central inventory of outsourced IT arrangements',
 'The bank maintains a central, current inventory of all outsourced IT services, including vendor, service, criticality and contract dates.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH,MEDIUM,LOW', 'Vendor register entry with all mandatory fields'),
('VS-INV-02', 'SUBCONTRACTING', 'Supply-chain dependencies mapped',
 'Dependencies on third parties, including key subcontractors in the vendor''s supply chain, are identified and recorded.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH', 'Disclosed subcontractor list; dependency map'),
('VS-RISK-01', 'RISK_CLASSIFICATION', 'Risk and materiality classification',
 'Each arrangement is classified by risk and materiality, and the classification is reviewed periodically.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH,MEDIUM,LOW', 'Documented materiality assessment'),
('VS-DD-01', 'DUE_DILIGENCE', 'Pre-onboarding due diligence',
 'Due diligence covering financial soundness, security posture, capability and concentration risk is performed before onboarding.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH,MEDIUM', 'Due diligence report and approval'),
('VS-DD-02', 'DUE_DILIGENCE', 'Periodic reassessment',
 'Material vendors are reassessed at least annually, and others at a frequency based on risk.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH', 'Assessment dated within review cycle'),
('VS-CON-01', 'CONTRACT', 'Audit and regulator access rights',
 'The contract grants the bank, its auditors and the regulator the right to access and inspect the vendor and its subcontractors.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH', 'Audit/inspection clause naming RBI'),
('VS-CON-02', 'INCIDENT_REPORTING', 'Incident notification obligation',
 'The contract requires the vendor to report cyber incidents to the bank without undue delay, with a defined timeline and recipient.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH,MEDIUM', 'Clause with time limit, recipient and content of notice'),
('VS-CON-03', 'SUBCONTRACTING', 'Control over subcontracting',
 'Material subcontracting requires the bank''s prior approval, and contractual obligations flow down to subcontractors.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH', 'Consent clause; flow-down clause; approved list'),
('VS-CON-04', 'DATA_PROTECTION', 'Data confidentiality, location and return',
 'The contract addresses confidentiality, data storage location, and return or deletion of data at exit.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH,MEDIUM', 'Data location, retention and deletion clauses'),
('VS-BCP-01', 'BUSINESS_CONTINUITY', 'Tested BCP/DR',
 'The vendor maintains a business continuity and DR plan that is tested periodically, with results shared with the bank.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH', 'BCP clause with RTO/RPO; latest DR test results'),
('VS-EXIT-01', 'EXIT', 'Exit strategy and transition support',
 'The arrangement has a documented exit strategy, and the contract provides for transition assistance without disruption.',
 'RBI outsourcing directions', 'TO VERIFY', 'CRITICAL,HIGH', 'Transition assistance clause; exit plan'),
('VS-SEC-01', 'ASSURANCE', 'Current independent assurance',
 'An independent assurance report or certification covering the services is current and its scope matches the services used.',
 'Internal policy', NULL, 'CRITICAL,HIGH', 'SOC 2 Type II within 12 months or bridge letter; ISO 27001 certificate'),
('VS-SEC-02', 'ASSURANCE', 'Subservice organisations covered',
 'Where assurance reports carve out subservice organisations, the bank obtains separate assurance over those organisations.',
 'Internal policy', NULL, 'CRITICAL,HIGH', 'Subservice org assurance report or documented compensating review');
