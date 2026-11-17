-- VendorShield AI: core schema (10 tables)
-- Graph: business_services -> applications -> vendors -> vendors (parent_vendor_id = fourth party)

-- 1. The regulated entity (one row for the demo, but keeps the model multi-tenant ready)
CREATE TABLE banks (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    short_code      VARCHAR(20)  NOT NULL UNIQUE,
    bank_type       VARCHAR(50)  NOT NULL,          -- e.g. COMMERCIAL, SMALL_FINANCE, COOPERATIVE
    headquarters    VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Vendors. parent_vendor_id is NULL for direct (third-party) vendors;
--    set it for subcontractors (fourth parties) of a direct vendor.
CREATE TABLE vendors (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    bank_id             BIGINT NOT NULL,
    parent_vendor_id    BIGINT NULL,
    name                VARCHAR(200) NOT NULL,
    service_category    VARCHAR(80)  NOT NULL,      -- CLOUD_HOSTING, PAYMENTS, KYC, ...
    service_description VARCHAR(500),
    risk_tier           VARCHAR(20)  NOT NULL,
    is_material         BOOLEAN NOT NULL DEFAULT FALSE,
    country             VARCHAR(60)  NOT NULL DEFAULT 'India',
    data_access         VARCHAR(30)  NOT NULL,      -- NONE, INTERNAL, CUSTOMER_PII, FINANCIAL
    contract_start      DATE,
    contract_end        DATE,
    last_assessed_on    DATE,
    next_review_due     DATE,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_vendor_bank   FOREIGN KEY (bank_id) REFERENCES banks(id),
    CONSTRAINT fk_vendor_parent FOREIGN KEY (parent_vendor_id) REFERENCES vendors(id),
    CONSTRAINT chk_vendor_tier   CHECK (risk_tier IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    CONSTRAINT chk_vendor_status CHECK (status IN ('ONBOARDING','ACTIVE','UNDER_REVIEW','EXITING','TERMINATED'))
    -- MySQL can't CHECK against an AUTO_INCREMENT column, so "vendor is not its own parent"
    -- and "no parent cycles" are enforced in the service layer.
);
CREATE INDEX idx_vendors_parent ON vendors(parent_vendor_id);

-- 3. Bank applications / IT systems
CREATE TABLE applications (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    bank_id         BIGINT NOT NULL,
    name            VARCHAR(200) NOT NULL,
    code            VARCHAR(30)  NOT NULL UNIQUE,
    description     VARCHAR(500),
    criticality     VARCHAR(20)  NOT NULL,
    owner_team      VARCHAR(100),
    rto_hours       INT,                            -- recovery time objective
    rpo_minutes     INT,                            -- recovery point objective
    CONSTRAINT fk_app_bank FOREIGN KEY (bank_id) REFERENCES banks(id),
    CONSTRAINT chk_app_crit CHECK (criticality IN ('CRITICAL','HIGH','MEDIUM','LOW'))
);

-- 4. Business services the customer actually experiences
CREATE TABLE business_services (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    bank_id                 BIGINT NOT NULL,
    name                    VARCHAR(200) NOT NULL,
    code                    VARCHAR(30)  NOT NULL UNIQUE,
    description             VARCHAR(500),
    is_customer_facing      BOOLEAN NOT NULL DEFAULT TRUE,
    criticality             VARCHAR(20) NOT NULL,
    impact_tolerance_hours  INT,                    -- max tolerable disruption
    bcp_plan_ref            VARCHAR(50),
    business_owner          VARCHAR(100),
    CONSTRAINT fk_svc_bank FOREIGN KEY (bank_id) REFERENCES banks(id),
    CONSTRAINT chk_svc_crit CHECK (criticality IN ('CRITICAL','HIGH','MEDIUM','LOW'))
);

-- 5. Which vendors each application depends on
CREATE TABLE application_vendor_dependencies (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    application_id      BIGINT NOT NULL,
    vendor_id           BIGINT NOT NULL,
    dependency_type     VARCHAR(40) NOT NULL,       -- HOSTING, PROCESSING, API, DATA, MESSAGING
    is_single_point     BOOLEAN NOT NULL DEFAULT FALSE,
    alternative_vendor  VARCHAR(200),
    notes               VARCHAR(500),
    CONSTRAINT fk_avd_app    FOREIGN KEY (application_id) REFERENCES applications(id),
    CONSTRAINT fk_avd_vendor FOREIGN KEY (vendor_id)      REFERENCES vendors(id),
    CONSTRAINT uq_avd UNIQUE (application_id, vendor_id, dependency_type)
);

-- 6. Which applications each business service depends on
CREATE TABLE service_application_dependencies (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_service_id BIGINT NOT NULL,
    application_id      BIGINT NOT NULL,
    is_essential        BOOLEAN NOT NULL DEFAULT TRUE, -- service fails without it
    CONSTRAINT fk_sad_svc FOREIGN KEY (business_service_id) REFERENCES business_services(id),
    CONSTRAINT fk_sad_app FOREIGN KEY (application_id)      REFERENCES applications(id),
    CONSTRAINT uq_sad UNIQUE (business_service_id, application_id)
);

-- 7. Uploaded evidence: contracts, SOC 2 / ISO reports, policies, incident notices
CREATE TABLE documents (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    vendor_id       BIGINT NOT NULL,
    doc_type        VARCHAR(40) NOT NULL,
    title           VARCHAR(300) NOT NULL,
    file_path       VARCHAR(500) NOT NULL,
    issued_on       DATE,
    valid_until     DATE,
    extracted_text  MEDIUMTEXT,                     -- filled by the ingestion step
    processing_status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    uploaded_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_doc_vendor FOREIGN KEY (vendor_id) REFERENCES vendors(id),
    CONSTRAINT chk_doc_type CHECK (doc_type IN ('CONTRACT','SOC2_REPORT','ISO_CERTIFICATE','AUDIT_REPORT','POLICY','BCP_PLAN','INCIDENT_NOTICE','OTHER')),
    CONSTRAINT chk_doc_status CHECK (processing_status IN ('UPLOADED','EXTRACTED','ANALYSED','FAILED'))
);

-- 8. Regulatory / internal control requirements the evidence is tested against
CREATE TABLE control_requirements (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                VARCHAR(30)  NOT NULL UNIQUE,
    theme               VARCHAR(60)  NOT NULL,      -- INVENTORY, DUE_DILIGENCE, SUBCONTRACTING, ...
    title               VARCHAR(250) NOT NULL,
    requirement_text    TEXT NOT NULL,
    source_framework    VARCHAR(100) NOT NULL,
    source_reference    VARCHAR(200),               -- paragraph ref, to be verified against official text
    applies_to_tiers    VARCHAR(100) NOT NULL DEFAULT 'CRITICAL,HIGH,MEDIUM,LOW',
    expected_evidence   VARCHAR(500)
);

-- 9. AI-proposed, human-reviewed link between a requirement and evidence
CREATE TABLE evidence_mappings (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    vendor_id               BIGINT NOT NULL,
    control_requirement_id  BIGINT NOT NULL,
    document_id             BIGINT NULL,            -- NULL when evidence is missing
    status                  VARCHAR(20) NOT NULL,
    evidence_excerpt        TEXT,                   -- quoted clause supporting the finding
    location_in_doc         VARCHAR(100),           -- e.g. "Clause 12.3, p.7"
    ai_rationale            TEXT,
    ai_confidence           DECIMAL(4,3),
    review_status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewed_by             VARCHAR(100),
    reviewed_at             TIMESTAMP NULL,
    reviewer_comment        VARCHAR(1000),
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_em_vendor  FOREIGN KEY (vendor_id)              REFERENCES vendors(id),
    CONSTRAINT fk_em_control FOREIGN KEY (control_requirement_id) REFERENCES control_requirements(id),
    CONSTRAINT fk_em_doc     FOREIGN KEY (document_id)            REFERENCES documents(id),
    CONSTRAINT chk_em_status CHECK (status IN ('MET','PARTIAL','NOT_MET','NOT_APPLICABLE')),
    CONSTRAINT chk_em_review CHECK (review_status IN ('PENDING','APPROVED','REJECTED','EDITED')),
    CONSTRAINT chk_em_conf   CHECK (ai_confidence IS NULL OR (ai_confidence >= 0 AND ai_confidence <= 1))
);
CREATE INDEX idx_em_vendor_control ON evidence_mappings(vendor_id, control_requirement_id);

-- 10. Incident notices received from vendors
CREATE TABLE incident_notices (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    vendor_id           BIGINT NOT NULL,            -- vendor where the incident happened
    reported_by_vendor_id BIGINT NOT NULL,          -- who told the bank (differs for fourth parties)
    document_id         BIGINT NULL,                -- raw notice text lives in documents
    reference_no       VARCHAR(50) NOT NULL UNIQUE,
    incident_type       VARCHAR(40) NOT NULL,
    severity_reported   VARCHAR(20) NOT NULL,
    summary             VARCHAR(1000) NOT NULL,
    detected_at         DATETIME NOT NULL,          -- when the vendor detected it
    notified_at         DATETIME NOT NULL,          -- when the bank was told
    resolved_at         DATETIME NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    CONSTRAINT fk_in_vendor FOREIGN KEY (vendor_id)   REFERENCES vendors(id),
    CONSTRAINT fk_in_reporter FOREIGN KEY (reported_by_vendor_id) REFERENCES vendors(id),
    CONSTRAINT fk_in_doc    FOREIGN KEY (document_id) REFERENCES documents(id),
    CONSTRAINT chk_in_type CHECK (incident_type IN ('OUTAGE','RANSOMWARE','DATA_BREACH','DEGRADATION','UNAUTHORISED_ACCESS','OTHER')),
    CONSTRAINT chk_in_sev  CHECK (severity_reported IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    CONSTRAINT chk_in_status CHECK (status IN ('RECEIVED','TRIAGING','ASSESSED','CLOSED'))
);
