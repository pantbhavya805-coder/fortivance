-- V8: hard usage limits for demo accounts (accounts created through Request a demo).
-- Counted on the account itself, so a demo reset or re-requesting the same email does not refill them.
-- Permanent accounts (judgedemo, the admin) have no demo_request_id and are not limited.
ALTER TABLE users
    ADD COLUMN incident_uploads_used  INT NOT NULL DEFAULT 0,
    ADD COLUMN register_imports_used  INT NOT NULL DEFAULT 0;
