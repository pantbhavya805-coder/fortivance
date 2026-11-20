-- V9: the judges' test account is called "judge" again (was "judgedemo" since V7). Only renamed when the
-- name is free; demo accounts use email addresses as usernames, so "judge" cannot clash with them.
UPDATE users SET username = 'judge'
 WHERE username = 'judgedemo'
   AND NOT EXISTS (SELECT 1 FROM (SELECT username FROM users) taken WHERE taken.username = 'judge');
