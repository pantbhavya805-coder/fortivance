-- ------------------------------------------------------------ separate admin console
-- bhavya becomes the dedicated ADMIN account (signs in only at /admin/login); judge becomes the permanent test
-- account judgedemo. Accounts can be soft-deleted by the admin; the admin may add up to 48 hours per demo.

ALTER TABLE users
    ADD COLUMN deleted_at    DATETIME NULL,
    ADD COLUMN last_login_at DATETIME NULL;

ALTER TABLE demo_requests
    ADD COLUMN admin_extension_hours INT NOT NULL DEFAULT 0;
ALTER TABLE demo_requests DROP CHECK chk_demo_request_status;
ALTER TABLE demo_requests ADD CONSTRAINT chk_demo_request_status
    CHECK (status IN ('PENDING', 'PROVISIONED', 'MAIL_FAILED', 'DELETED'));
ALTER TABLE demo_requests ADD CONSTRAINT chk_demo_admin_ext CHECK (admin_extension_hours BETWEEN 0 AND 48);

UPDATE users SET username = 'judgedemo', display_name = 'Judge (demo test account)'
 WHERE username = 'judge'
   AND NOT EXISTS (SELECT 1 FROM (SELECT username FROM users) taken WHERE taken.username = 'judgedemo');
UPDATE users SET role = 'ADMIN' WHERE username = 'bhavya';

-- Accounts already switched off by earlier migrations are retired: the shared/paired permanent accounts
-- (V4, V5) and the approver half of pre-V5 demo requests (V6). Kept for references, hidden from the console.
UPDATE users SET deleted_at = NOW()
 WHERE active = FALSE AND deleted_at IS NULL
   AND (demo_request_id IS NULL
        OR username IN (SELECT approver_username FROM (SELECT approver_username FROM demo_requests
                                                        WHERE username IS NOT NULL AND username = email) old));
