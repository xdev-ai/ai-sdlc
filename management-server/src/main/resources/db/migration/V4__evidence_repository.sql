CREATE TABLE evidence_assets (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES projects(id),
  validation_evidence_id UUID REFERENCES validation_evidences(id),
  asset_type VARCHAR(32) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(255) NOT NULL,
  size_bytes BIGINT NOT NULL,
  s3_bucket VARCHAR(255) NOT NULL,
  s3_key VARCHAR(1024) NOT NULL,
  sha256_digest VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(120) NOT NULL,
  object_lock_mode VARCHAR(20),
  retention_until TIMESTAMPTZ,
  uploaded_by VARCHAR(120) NOT NULL,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  access_level VARCHAR(20) NOT NULL DEFAULT 'PROJECT',
  deleted_at TIMESTAMPTZ,
  CONSTRAINT evidence_asset_size_ck CHECK (size_bytes >= 0),
  CONSTRAINT evidence_asset_sha256_ck CHECK (sha256_digest ~ '^[a-f0-9]{64}$'),
  CONSTRAINT evidence_asset_access_level_ck CHECK (access_level IN ('PROJECT', 'REVIEWERS', 'OWNERS')),
  CONSTRAINT evidence_asset_lock_ck CHECK (
    (object_lock_mode IS NULL AND retention_until IS NULL)
    OR (object_lock_mode IN ('GOVERNANCE', 'COMPLIANCE') AND retention_until IS NOT NULL AND retention_until >= uploaded_at)
  ),
  CONSTRAINT evidence_asset_object_uq UNIQUE (s3_bucket, s3_key),
  CONSTRAINT evidence_asset_idempotency_uq UNIQUE (project_id, idempotency_key)
);
CREATE INDEX evidence_assets_project_active_uploaded_idx ON evidence_assets(project_id, uploaded_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX evidence_assets_validation_evidence_idx ON evidence_assets(validation_evidence_id) WHERE deleted_at IS NULL;
CREATE INDEX evidence_assets_retention_idx ON evidence_assets(retention_until) WHERE deleted_at IS NULL AND retention_until IS NOT NULL;
