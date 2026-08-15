ALTER TABLE findings
  ADD CONSTRAINT findings_triage_status_ck CHECK (triage_status IN ('OPEN', 'ACKNOWLEDGED', 'ACCEPTED_RISK', 'FALSE_POSITIVE', 'RESOLVED'));

ALTER TABLE validation_evidences
  ADD CONSTRAINT evidence_retention_after_created_ck CHECK (retention_until IS NULL OR retention_until >= created_at);

CREATE INDEX findings_run_triage_idx ON findings(validation_run_id, triage_status, severity);
CREATE INDEX evidence_retention_cleanup_idx ON validation_evidences(retention_until) WHERE retention_until IS NOT NULL;
