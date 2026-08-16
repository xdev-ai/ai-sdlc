CREATE TABLE scm_repository_links (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES projects(id),
  provider VARCHAR(20) NOT NULL,
  repository_full_name VARCHAR(300) NOT NULL,
  installation_id BIGINT,
  default_branch VARCHAR(255),
  policy_gate_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by VARCHAR(120) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT scm_repository_provider_name_uq UNIQUE (provider, repository_full_name),
  CONSTRAINT scm_repository_project_provider_name_uq UNIQUE (project_id, provider, repository_full_name)
);

CREATE TABLE scm_events (
  id UUID PRIMARY KEY,
  project_id UUID NOT NULL REFERENCES projects(id),
  repository_link_id UUID NOT NULL REFERENCES scm_repository_links(id),
  provider VARCHAR(20) NOT NULL,
  delivery_id VARCHAR(120) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  action VARCHAR(120),
  repository_full_name VARCHAR(300) NOT NULL,
  installation_id BIGINT,
  ref VARCHAR(500),
  commit_sha VARCHAR(80),
  pull_request_number INTEGER,
  workflow_run_id BIGINT,
  release_tag VARCHAR(300),
  validation_run_id UUID REFERENCES validation_runs(id),
  policy_check_run_id BIGINT,
  payload_sha256 VARCHAR(64) NOT NULL,
  payload JSONB NOT NULL,
  processing_status VARCHAR(20) NOT NULL,
  failure_reason TEXT,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  CONSTRAINT scm_event_provider_delivery_uq UNIQUE (provider, delivery_id)
);

CREATE INDEX scm_repository_project_idx ON scm_repository_links(project_id, provider, repository_full_name);
CREATE INDEX scm_events_project_received_idx ON scm_events(project_id, received_at DESC);
CREATE INDEX scm_events_project_pr_idx ON scm_events(project_id, pull_request_number, received_at DESC);
CREATE INDEX scm_events_validation_idx ON scm_events(validation_run_id);
