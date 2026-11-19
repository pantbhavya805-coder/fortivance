-- V3: passages, users, incident workflow, AI outputs, token usage, audit log, actions.
-- Also: regulatory scope on vendors, demo flag on requirements, VeriKYC gapped contract.

-- ------------------------------------------------------------ vendor additions
ALTER TABLE vendors
    ADD COLUMN email_domain VARCHAR(120) NULL AFTER country,
    ADD COLUMN outsourcing_scope VARCHAR(20) NOT NULL DEFAULT 'IN_SCOPE' AFTER email_domain,
    ADD COLUMN scope_note VARCHAR(300) NULL AFTER outsourcing_scope,
    ADD CONSTRAINT chk_vendor_scope CHECK (outsourcing_scope IN ('IN_SCOPE','EXCLUDED','UNDER_REVIEW'));

UPDATE vendors SET email_domain = 'nimbuscloud.example' WHERE id = 1;
UPDATE vendors SET email_domain = 'payrail.example'     WHERE id = 2;
UPDATE vendors SET email_domain = 'verikyc.example'     WHERE id = 3;
UPDATE vendors SET email_domain = 'notifyhub.example'   WHERE id = 4;
UPDATE vendors SET email_domain = 'securevault.example' WHERE id = 5;
UPDATE vendors SET email_domain = 'textlink.example'    WHERE id = 6;

-- RBI outsourcing Directions 2025, para 5(2)(iii): IT outsourcing provisions do not apply to SMS gateways.
UPDATE vendors
   SET outsourcing_scope = 'EXCLUDED',
       scope_note = 'SMS gateway: excluded from IT outsourcing provisions (RBI Outsourcing Directions 2025, para 5(2)(iii)). Still in scope for incident and continuity monitoring.'
 WHERE id IN (4, 6);

-- ------------------------------------------------------------ documents: hash for result caching
ALTER TABLE documents
    ADD COLUMN content_hash CHAR(64) NULL AFTER file_path,
    ADD COLUMN page_count INT NULL AFTER content_hash;
CREATE INDEX idx_documents_hash ON documents(content_hash);

-- Contract with gaps now belongs to an in-scope vendor (VeriKYC).
INSERT INTO documents (id, vendor_id, doc_type, title, file_path, issued_on, valid_until, processing_status) VALUES
(7, 3, 'CONTRACT', 'VeriKYC Technology Services Agreement VK/BFSI/2024/112', 'contracts/verikyc-services-agreement.md', '2024-02-01', '2027-01-31', 'UPLOADED');

-- Seeded incidents are removed: incidents are now created by the upload pipeline.
-- The notice files stay in sample-docs/incident-notices as upload inputs.
DELETE FROM incident_notices;
DELETE FROM documents WHERE doc_type = 'INCIDENT_NOTICE';

-- ------------------------------------------------------------ 11. passages (citation units)
CREATE TABLE document_passages (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id     BIGINT NOT NULL,
    passage_key     VARCHAR(20) NOT NULL,          -- e.g. p3-c2 (page 3, chunk 2)
    page_no         INT NOT NULL,
    section_ref     VARCHAR(100),                  -- e.g. "Clause 5.1"
    text            TEXT NOT NULL,
    CONSTRAINT fk_passage_doc FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    CONSTRAINT uq_passage UNIQUE (document_id, passage_key)
);

ALTER TABLE evidence_mappings
    ADD COLUMN passage_id BIGINT NULL AFTER document_id,
    ADD CONSTRAINT fk_em_passage FOREIGN KEY (passage_id) REFERENCES document_passages(id);

-- ------------------------------------------------------------ requirements used in the demo
ALTER TABLE control_requirements
    ADD COLUMN in_demo BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE control_requirements SET in_demo = TRUE
 WHERE code IN ('VS-CON-01','VS-CON-02','VS-CON-03','VS-BCP-01','VS-SEC-02');

-- Paragraph references from the RBI Directions text; confirm before quoting externally.
UPDATE control_requirements SET source_reference = 'Para 77 (verify)'                    WHERE code IN ('VS-INV-01','VS-INV-02');
UPDATE control_requirements SET source_reference = 'Para 69(xii) (verify)'               WHERE code = 'VS-CON-01';
UPDATE control_requirements SET source_reference = 'Para 56; Para 69(iii) (verify)'      WHERE code = 'VS-CON-02';
UPDATE control_requirements SET source_reference = 'Para 4(2); Para 69(i), (xiii) (verify)' WHERE code = 'VS-CON-03';
UPDATE control_requirements SET source_reference = 'Para 78, applying paras 40-43 (verify)' WHERE code = 'VS-BCP-01';
UPDATE control_requirements SET source_reference = 'Paras 79-83 (verify)'                WHERE code = 'VS-EXIT-01';
UPDATE control_requirements
   SET source_framework = 'RBI (Commercial Banks - Managing Risks in Outsourcing) Directions, 2025'
 WHERE source_framework = 'RBI outsourcing directions';

-- ------------------------------------------------------------ 12. users (2 demo roles)
CREATE TABLE users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(50) NOT NULL UNIQUE,
    display_name    VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,          -- bcrypt
    role            VARCHAR(20) NOT NULL,
    team            VARCHAR(100),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_user_role CHECK (role IN ('ANALYST','APPROVER','ADMIN'))
);

-- Demo password for both: Fortivance@2026
INSERT INTO users (id, username, display_name, password_hash, role, team) VALUES
(1, 'analyst',  'Priya Nair (TPRM Analyst)',  '$2a$10$NFUwqxYKOP2ufCCpx6XIB.QstqIa9dEh60zwnvHePJY77iEg6yIuS', 'ANALYST',  'Third-Party Risk'),
(2, 'approver', 'Arvind Rao (Head of TPRM)',  '$2a$10$1LP2iJ/4Mb6zyMKBfGCNU.yIZFn/OJaqNWGEykoq98wqnlSQL1qja', 'APPROVER', 'Third-Party Risk');

-- ------------------------------------------------------------ incident additions
ALTER TABLE incident_notices
    ADD COLUMN received_at DATETIME NULL AFTER notified_at,          -- when uploaded into Fortivance
    ADD COLUMN sender_email VARCHAR(200) NULL AFTER received_at,
    ADD COLUMN extraction_confidence DECIMAL(4,3) NULL,
    ADD COLUMN created_by BIGINT NULL,
    ADD CONSTRAINT fk_in_user FOREIGN KEY (created_by) REFERENCES users(id);

-- 13. Snapshot of the computed impact at triage time (what the analyst saw)
CREATE TABLE incident_impacts (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id         BIGINT NOT NULL,
    business_service_id BIGINT NOT NULL,
    impact_level        VARCHAR(20) NOT NULL,       -- DISRUPTED (essential app hit) / DEGRADED
    via_applications    VARCHAR(300),
    vendor_path         VARCHAR(500),
    computed_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ii_incident FOREIGN KEY (incident_id) REFERENCES incident_notices(id) ON DELETE CASCADE,
    CONSTRAINT fk_ii_service  FOREIGN KEY (business_service_id) REFERENCES business_services(id),
    CONSTRAINT chk_ii_level CHECK (impact_level IN ('DISRUPTED','DEGRADED'))
);

-- 14. Incident timeline
CREATE TABLE incident_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id     BIGINT NOT NULL,
    event_type      VARCHAR(40) NOT NULL,   -- DETECTED_BY_VENDOR, NOTICE_RECEIVED, EXTRACTED, IMPACT_COMPUTED, PLAN_DRAFTED, PLAN_APPROVED, ACTION_ASSIGNED, ...
    occurred_at     DATETIME NOT NULL,
    actor           VARCHAR(100) NOT NULL,  -- username, SYSTEM or AI:<model>
    description     VARCHAR(1000),
    CONSTRAINT fk_ie_incident FOREIGN KEY (incident_id) REFERENCES incident_notices(id) ON DELETE CASCADE
);
CREATE INDEX idx_ie_incident_time ON incident_events(incident_id, occurred_at);

-- ------------------------------------------------------------ 15. AI outputs (also the result cache)
CREATE TABLE ai_outputs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    output_type     VARCHAR(40) NOT NULL,   -- INCIDENT_EXTRACTION, RESPONSE_PLAN, EVIDENCE_MAPPING, ESCALATION_NOTE
    incident_id     BIGINT NULL,
    document_id     BIGINT NULL,
    model           VARCHAR(60) NOT NULL,
    prompt_version  VARCHAR(20) NOT NULL,
    input_hash      CHAR(64) NOT NULL,      -- sha256 of prompt version + inputs; identical input = cache hit
    content_json    JSON NOT NULL,
    review_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewed_by     BIGINT NULL,
    reviewed_at     DATETIME NULL,
    review_comment  VARCHAR(1000),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ao_incident FOREIGN KEY (incident_id) REFERENCES incident_notices(id) ON DELETE CASCADE,
    CONSTRAINT fk_ao_doc      FOREIGN KEY (document_id) REFERENCES documents(id),
    CONSTRAINT fk_ao_user     FOREIGN KEY (reviewed_by) REFERENCES users(id),
    CONSTRAINT chk_ao_review CHECK (review_status IN ('PENDING','APPROVED','REJECTED','EDITED')),
    CONSTRAINT uq_ao_cache UNIQUE (output_type, model, input_hash)
);

-- ------------------------------------------------------------ 16. token usage per Claude call
CREATE TABLE llm_usage (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    call_type               VARCHAR(40) NOT NULL,
    model                   VARCHAR(60) NOT NULL,
    input_tokens            INT NOT NULL DEFAULT 0,
    output_tokens           INT NOT NULL DEFAULT 0,
    cache_creation_tokens   INT NOT NULL DEFAULT 0,
    cache_read_tokens       INT NOT NULL DEFAULT 0,
    estimated_cost_usd      DECIMAL(10,6) NOT NULL DEFAULT 0,
    latency_ms              INT,
    served_from_cache       BOOLEAN NOT NULL DEFAULT FALSE,  -- result cache hit: no API call made
    is_batch                BOOLEAN NOT NULL DEFAULT FALSE,
    success                 BOOLEAN NOT NULL DEFAULT TRUE,
    error_message           VARCHAR(500),
    incident_id             BIGINT NULL,
    document_id             BIGINT NULL,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_usage_day ON llm_usage(created_at);

-- ------------------------------------------------------------ 17. audit log
CREATE TABLE audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor           VARCHAR(100) NOT NULL,
    action          VARCHAR(60) NOT NULL,
    entity_type     VARCHAR(40) NOT NULL,
    entity_id       BIGINT NULL,
    detail          JSON NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);

-- ------------------------------------------------------------ 18. actions
CREATE TABLE actions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_id     BIGINT NULL,
    vendor_id       BIGINT NULL,
    evidence_mapping_id BIGINT NULL,
    title           VARCHAR(300) NOT NULL,
    owner_team      VARCHAR(100),
    assigned_to     BIGINT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    priority        VARCHAR(10) NOT NULL DEFAULT 'MEDIUM',
    source          VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    due_at          DATETIME NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at       DATETIME NULL,
    CONSTRAINT fk_act_incident FOREIGN KEY (incident_id) REFERENCES incident_notices(id) ON DELETE CASCADE,
    CONSTRAINT fk_act_vendor   FOREIGN KEY (vendor_id)   REFERENCES vendors(id),
    CONSTRAINT fk_act_em       FOREIGN KEY (evidence_mapping_id) REFERENCES evidence_mappings(id),
    CONSTRAINT fk_act_user     FOREIGN KEY (assigned_to) REFERENCES users(id),
    CONSTRAINT chk_act_status   CHECK (status IN ('OPEN','IN_PROGRESS','DONE','CANCELLED')),
    CONSTRAINT chk_act_priority CHECK (priority IN ('HIGH','MEDIUM','LOW')),
    CONSTRAINT chk_act_source   CHECK (source IN ('AI','MANUAL'))
);
