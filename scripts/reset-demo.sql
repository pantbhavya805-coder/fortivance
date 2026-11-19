-- Fortivance: demo reset script
-- Clears incident workflow data; keeps vendors, contracts, requirements and master data.
-- AI results are detached, not deleted, so re-uploading the same notices during rehearsal
-- is served from the result cache at no token cost.

UPDATE ai_outputs SET incident_id = NULL, document_id = NULL,
       review_status = 'PENDING', reviewed_by = NULL, reviewed_at = NULL, review_comment = NULL
 WHERE output_type IN ('INCIDENT_EXTRACTION', 'RESPONSE_PLAN', 'ESCALATION_NOTE');
DELETE FROM actions;
DELETE FROM incident_events;
DELETE FROM incident_impacts;
DELETE FROM incident_notices;
DELETE FROM documents WHERE doc_type = 'INCIDENT_NOTICE';
DELETE FROM audit_log;
