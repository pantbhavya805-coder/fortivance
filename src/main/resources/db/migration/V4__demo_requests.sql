-- ------------------------------------------------------------ demo access requests
-- One row per email address. Rows are never deleted; expiry and extensions are tracked here.
CREATE TABLE demo_requests (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    email               VARCHAR(200)  NOT NULL,             -- stored lower-case
    name                VARCHAR(100)  NOT NULL,             -- person or organisation
    phone               VARCHAR(20)   NULL,
    purpose             VARCHAR(1000) NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    analyst_username    VARCHAR(50)   NULL,
    approver_username   VARCHAR(50)   NULL,
    access_expires_at   DATETIME      NULL,
    extension_count     INT           NOT NULL DEFAULT 0,
    client_ip           VARCHAR(45)   NULL,
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mail_sent_at        DATETIME      NULL,
    CONSTRAINT uk_demo_requests_email UNIQUE (email),
    CONSTRAINT chk_demo_request_status CHECK (status IN ('PENDING', 'PROVISIONED', 'MAIL_FAILED')),
    CONSTRAINT chk_demo_request_ext CHECK (extension_count >= 0)
);
CREATE INDEX idx_demo_requests_created ON demo_requests(created_at);

-- Demo accounts expire; permanent accounts (owner, judges) keep expires_at NULL.
ALTER TABLE users
    ADD COLUMN expires_at      DATETIME NULL,
    ADD COLUMN demo_request_id BIGINT   NULL,
    ADD CONSTRAINT fk_users_demo_request FOREIGN KEY (demo_request_id) REFERENCES demo_requests(id);
CREATE INDEX idx_users_demo_request ON users(demo_request_id);

-- The shared demo accounts' password is public (git history), so they are switched off.
-- Kept, not deleted: incidents, AI outputs and actions reference them.
UPDATE users SET active = FALSE WHERE username IN ('analyst', 'approver');
