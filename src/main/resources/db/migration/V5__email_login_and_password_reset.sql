-- ------------------------------------------------------------ email login, role switching, password reset
-- Demo accounts now sign in with their email address, so usernames (and every column that records a
-- username) must hold up to 200 characters.
ALTER TABLE users             MODIFY username    VARCHAR(200) NOT NULL;
ALTER TABLE audit_log         MODIFY actor       VARCHAR(200) NOT NULL;
ALTER TABLE incident_events   MODIFY actor       VARCHAR(200) NOT NULL;
ALTER TABLE evidence_mappings MODIFY reviewed_by VARCHAR(200) NULL;

-- One account per demo request (username = email). The analyst/approver columns stay for requests made
-- before this change; their accounts keep working.
ALTER TABLE demo_requests ADD COLUMN username VARCHAR(200) NULL AFTER approver_username;

-- One-time password reset links. Only the SHA-256 of the token is stored; the token itself exists only in
-- the email that was sent.
CREATE TABLE password_reset_tokens (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT   NOT NULL,
    token_hash      CHAR(64) NOT NULL,
    expires_at      DATETIME NOT NULL,
    used_at         DATETIME NULL,
    requested_ip    VARCHAR(45) NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_password_reset_token UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_password_reset_user ON password_reset_tokens(user_id, created_at);

-- The owner and judge pairs are replaced by single switchable accounts (bhavya, judge), created at startup.
-- Kept, not deleted: incidents, AI outputs and actions may reference them.
UPDATE users SET active = FALSE
 WHERE username IN ('bhavya_analyst', 'bhavya_approver', 'judge_analyst', 'judge_approver');
