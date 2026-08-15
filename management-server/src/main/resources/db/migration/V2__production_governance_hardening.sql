ALTER TABLE spec_kits
  ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN deprecated_at TIMESTAMPTZ,
  ADD COLUMN deprecated_by VARCHAR(120),
  ADD COLUMN deprecation_reason TEXT;

ALTER TABLE policies
  ADD COLUMN activated_at TIMESTAMPTZ,
  ADD COLUMN activated_by VARCHAR(120),
  ADD COLUMN deactivated_at TIMESTAMPTZ,
  ADD COLUMN deactivated_by VARCHAR(120);

ALTER TABLE constitutions
  ADD COLUMN activated_at TIMESTAMPTZ,
  ADD COLUMN activated_by VARCHAR(120),
  ADD COLUMN deactivated_at TIMESTAMPTZ,
  ADD COLUMN deactivated_by VARCHAR(120);

ALTER TABLE exception_requests
  ADD COLUMN decision_note TEXT,
  ADD COLUMN expires_at TIMESTAMPTZ;

ALTER TABLE findings
  ADD COLUMN triage_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  ADD COLUMN triaged_by VARCHAR(120),
  ADD COLUMN triaged_at TIMESTAMPTZ,
  ADD COLUMN triage_note TEXT;

ALTER TABLE validation_evidences
  ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN retention_until TIMESTAMPTZ;

ALTER TABLE review_items
  ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE quality_metric_snapshots
  ADD CONSTRAINT quality_metric_period_order_ck CHECK (period_end > period_start),
  ADD CONSTRAINT quality_metric_nonnegative_ck CHECK (
    (deployment_frequency IS NULL OR deployment_frequency >= 0) AND
    (lead_time_hours IS NULL OR lead_time_hours >= 0) AND
    (change_failure_rate IS NULL OR change_failure_rate BETWEEN 0 AND 1) AND
    (pr_review_time_delta_hours IS NULL OR pr_review_time_delta_hours >= 0) AND
    (rework_rate IS NULL OR rework_rate BETWEEN 0 AND 1) AND
    (review_queue_health IS NULL OR review_queue_health BETWEEN 0 AND 1) AND
    (spec_alignment_score IS NULL OR spec_alignment_score BETWEEN 0 AND 1)
  );

CREATE UNIQUE INDEX project_kit_unique_assignment_uq ON project_kits(project_id, spec_kit_id);
CREATE UNIQUE INDEX policy_scope_version_uq ON policies(organization_id, COALESCE(project_id, '00000000-0000-0000-0000-000000000000'::uuid), key, version);
CREATE UNIQUE INDEX constitution_scope_version_uq ON constitutions(organization_id, COALESCE(project_id, '00000000-0000-0000-0000-000000000000'::uuid), version);
CREATE INDEX projects_org_created_idx ON projects(organization_id, created_at DESC);
CREATE INDEX memberships_project_role_idx ON project_memberships(project_id, role);
CREATE INDEX spec_kits_org_lifecycle_idx ON spec_kits(organization_id, lifecycle_status, slug, created_at DESC);
CREATE INDEX policies_scope_active_idx ON policies(organization_id, project_id, active, key, created_at DESC);
CREATE INDEX constitutions_scope_active_idx ON constitutions(organization_id, project_id, active, created_at DESC);
CREATE INDEX exception_requests_project_status_idx ON exception_requests(project_id, status, created_at DESC);
CREATE INDEX findings_run_severity_idx ON findings(validation_run_id, severity, triage_status);
CREATE INDEX evidence_run_created_idx ON validation_evidences(validation_run_id, created_at DESC);
CREATE INDEX review_items_project_status_idx ON review_items(project_id, status, created_at DESC);
CREATE INDEX quality_snapshots_project_period_idx ON quality_metric_snapshots(project_id, period_end DESC);
CREATE INDEX audit_events_org_action_idx ON audit_events(organization_id, action, sequence DESC);
