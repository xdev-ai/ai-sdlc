alter table quality_metric_snapshots
  add column if not exists model_use_distribution jsonb not null default '{}'::jsonb,
  add column if not exists security_debt_score numeric(8,4);

create table risk_scores (
  id uuid primary key,
  project_id uuid not null references projects(id),
  score integer not null,
  risk_band varchar(20) not null,
  formula_version varchar(40) not null,
  components jsonb not null,
  source_summary jsonb not null,
  calculated_by varchar(200) not null,
  calculated_at timestamptz not null default current_timestamp,
  constraint risk_scores_score_ck check (score between 0 and 100),
  constraint risk_scores_band_ck check (risk_band in ('LOW', 'MODERATE', 'HIGH', 'CRITICAL')),
  constraint risk_scores_formula_version_ck check (formula_version ~ '^[a-z0-9._-]{3,40}$')
);
create index risk_scores_project_calculated_idx on risk_scores(project_id, calculated_at desc);
