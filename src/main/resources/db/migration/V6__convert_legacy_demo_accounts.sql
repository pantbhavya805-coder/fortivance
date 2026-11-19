-- ------------------------------------------------------------ convert pre-V5 demo requests to email login
-- Requests made before V5 got two accounts (<name>-analyst / <name>-approver). Convert each to the current
-- model: the analyst account becomes the single switchable account and is renamed to the request's email
-- (its password is unchanged); the approver account is switched off (kept, not deleted, for references).
-- A no-op on databases that never had such requests.

-- Skip a request if its email is already some other account's username (would break the unique key).
UPDATE users u
  JOIN demo_requests d ON u.username = d.analyst_username AND u.demo_request_id = d.id
   SET u.username = d.email
 WHERE d.username IS NULL
   AND NOT EXISTS (SELECT 1 FROM (SELECT username FROM users) taken WHERE taken.username = d.email);

UPDATE demo_requests d
   SET d.username = d.email
 WHERE d.username IS NULL
   AND EXISTS (SELECT 1 FROM (SELECT username FROM users) u WHERE u.username = d.email);

UPDATE users u
  JOIN demo_requests d ON u.username = d.approver_username AND u.demo_request_id = d.id
   SET u.active = FALSE
 WHERE d.username = d.email;
