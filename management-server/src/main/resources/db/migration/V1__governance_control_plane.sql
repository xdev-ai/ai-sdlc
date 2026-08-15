CREATE TABLE organizations (
  id UUID PRIMARY KEY, slug VARCHAR(80) NOT NULL UNIQUE, name VARCHAR(160) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE projects (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id), slug VARCHAR(80) NOT NULL, name VARCHAR(160) NOT NULL, description TEXT, status VARCHAR(20) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT project_org_slug_uq UNIQUE (organization_id, slug)
);
CREATE TABLE project_memberships (
  id UUID PRIMARY KEY, project_id UUID NOT NULL REFERENCES projects(id), subject VARCHAR(120) NOT NULL, role VARCHAR(20) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT project_subject_uq UNIQUE (project_id, subject)
);
CREATE TABLE spec_kits (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id), slug VARCHAR(100) NOT NULL, version VARCHAR(80) NOT NULL, layer VARCHAR(20) NOT NULL, parent_kit_id UUID, manifest JSONB NOT NULL DEFAULT '{}'::jsonb, pinned BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT spec_kit_version_uq UNIQUE (organization_id, slug, version, layer)
);
CREATE TABLE project_kits (
  id UUID PRIMARY KEY, project_id UUID NOT NULL REFERENCES projects(id), spec_kit_id UUID NOT NULL REFERENCES spec_kits(id), precedence SMALLINT NOT NULL, pinned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT project_kit_precedence_uq UNIQUE (project_id, precedence)
);
CREATE TABLE constitutions (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id), project_id UUID REFERENCES projects(id), version VARCHAR(80) NOT NULL, content TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE policies (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id), project_id UUID REFERENCES projects(id), key VARCHAR(160) NOT NULL, version VARCHAR(80) NOT NULL, rule JSONB NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT policy_version_uq UNIQUE (organization_id, project_id, key, version)
);
CREATE TABLE capability_grants (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id), project_id UUID REFERENCES projects(id), subject VARCHAR(120) NOT NULL, capability VARCHAR(160) NOT NULL, expires_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE exception_requests (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id), project_id UUID REFERENCES projects(id), requested_by VARCHAR(120) NOT NULL, policy_key VARCHAR(160) NOT NULL, rationale TEXT NOT NULL, status VARCHAR(30) NOT NULL, decided_by VARCHAR(120), decided_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE validation_runs (
  id UUID PRIMARY KEY, project_id UUID NOT NULL REFERENCES projects(id), idempotency_key VARCHAR(120) NOT NULL, status VARCHAR(20) NOT NULL, cli_version VARCHAR(120) NOT NULL, kit_version VARCHAR(160) NOT NULL, model_pin VARCHAR(160) NOT NULL, actor_subject VARCHAR(120) NOT NULL, completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT validation_project_idempotency_uq UNIQUE (project_id, idempotency_key)
);
CREATE TABLE findings (
  id UUID PRIMARY KEY, validation_run_id UUID NOT NULL REFERENCES validation_runs(id), severity VARCHAR(20) NOT NULL, code VARCHAR(100) NOT NULL, message TEXT NOT NULL, path VARCHAR(400), line INTEGER, evidence_uri VARCHAR(1000)
);
CREATE TABLE validation_evidences (
  id UUID PRIMARY KEY, validation_run_id UUID NOT NULL REFERENCES validation_runs(id), evidence_type VARCHAR(80) NOT NULL, digest_sha256 VARCHAR(64) NOT NULL, uri VARCHAR(1000), metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE TABLE trace_nodes (
  id UUID PRIMARY KEY, project_id UUID NOT NULL REFERENCES projects(id), node_type VARCHAR(20) NOT NULL, external_key VARCHAR(160) NOT NULL, label VARCHAR(300) NOT NULL, status VARCHAR(50), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT trace_node_key_uq UNIQUE (project_id, external_key)
);
CREATE TABLE trace_edges (
  id UUID PRIMARY KEY, project_id UUID NOT NULL REFERENCES projects(id), source_node_id UUID NOT NULL REFERENCES trace_nodes(id), target_node_id UUID NOT NULL REFERENCES trace_nodes(id), relation VARCHAR(80) NOT NULL,
  CONSTRAINT trace_edge_uq UNIQUE (source_node_id, target_node_id, relation)
);
CREATE TABLE review_items (
  id UUID PRIMARY KEY, project_id UUID NOT NULL REFERENCES projects(id), review_type VARCHAR(30) NOT NULL, title VARCHAR(300) NOT NULL, status VARCHAR(30) NOT NULL, requested_by VARCHAR(120) NOT NULL, decided_by VARCHAR(120), decision_note TEXT, decided_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE quality_metric_snapshots (
  id UUID PRIMARY KEY, project_id UUID NOT NULL REFERENCES projects(id), period_start TIMESTAMPTZ NOT NULL, period_end TIMESTAMPTZ NOT NULL, deployment_frequency NUMERIC(14,4), lead_time_hours NUMERIC(14,4), change_failure_rate NUMERIC(8,4), pr_review_time_delta_hours NUMERIC(14,4), rework_rate NUMERIC(8,4), review_queue_health NUMERIC(8,4), spec_alignment_score NUMERIC(8,4), calculated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT quality_metric_period_uq UNIQUE (project_id, period_start, period_end)
);
CREATE TABLE audit_events (
  id UUID PRIMARY KEY, organization_id UUID NOT NULL REFERENCES organizations(id), project_id UUID REFERENCES projects(id), actor_subject VARCHAR(120) NOT NULL, action VARCHAR(160) NOT NULL, entity_type VARCHAR(100) NOT NULL, entity_id VARCHAR(160), payload JSONB, sequence BIGINT NOT NULL, previous_hash VARCHAR(64) NOT NULL, event_hash VARCHAR(64) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT audit_org_sequence_uq UNIQUE (organization_id, sequence), CONSTRAINT audit_hash_uq UNIQUE (event_hash)
);
CREATE INDEX validation_runs_project_completed_idx ON validation_runs(project_id, completed_at DESC);
CREATE INDEX findings_run_idx ON findings(validation_run_id);
CREATE INDEX audit_events_org_sequence_idx ON audit_events(organization_id, sequence DESC);
CREATE OR REPLACE FUNCTION block_audit_event_mutation() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'audit_events is append-only'; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER audit_events_no_update BEFORE UPDATE ON audit_events FOR EACH ROW EXECUTE FUNCTION block_audit_event_mutation();
CREATE TRIGGER audit_events_no_delete BEFORE DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION block_audit_event_mutation();

