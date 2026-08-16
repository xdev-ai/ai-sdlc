create table inference_usage_events (
  id uuid primary key,
  project_id uuid not null references projects(id),
  agent_session_id uuid references agent_sessions(id),
  source_event_key varchar(240) not null,
  provider varchar(160) not null,
  model_name varchar(240) not null,
  model_version varchar(240),
  occurred_at timestamptz not null,
  input_tokens bigint not null check (input_tokens >= 0),
  output_tokens bigint not null check (output_tokens >= 0),
  currency_code char(3) not null,
  source_cost_minor bigint not null check (source_cost_minor >= 0),
  source_claim_sha256 varchar(64) not null,
  recorded_by varchar(240) not null,
  recorded_at timestamptz not null default now(),
  unique(project_id, source_event_key)
);
create index inference_usage_events_project_occurred_idx on inference_usage_events(project_id, occurred_at desc);

create table inference_cost_allocations (
  id uuid primary key,
  usage_event_id uuid not null unique references inference_usage_events(id),
  project_id uuid not null references projects(id),
  allocation_key varchar(240) not null,
  currency_code char(3) not null,
  allocated_cost_minor bigint not null check (allocated_cost_minor >= 0),
  allocation_method varchar(80) not null,
  allocation_evidence_sha256 varchar(64) not null,
  allocated_at timestamptz not null default now()
);
create index inference_cost_allocations_project_allocated_idx on inference_cost_allocations(project_id, allocated_at desc);

create table inference_cost_forecasts (
  id uuid primary key,
  project_id uuid not null references projects(id),
  forecast_start date not null,
  horizon_days integer not null check (horizon_days between 1 and 90),
  currency_code char(3) not null,
  predicted_cost_minor bigint,
  lower_bound_minor bigint,
  upper_bound_minor bigint,
  sample_days integer not null check (sample_days >= 0),
  methodology varchar(160) not null,
  status varchar(40) not null,
  evidence_sha256 varchar(64) not null,
  generated_by varchar(240) not null,
  generated_at timestamptz not null default now()
);
create index inference_cost_forecasts_project_generated_idx on inference_cost_forecasts(project_id, generated_at desc);

create table runtime_ai_decisions (
  id uuid primary key,
  project_id uuid not null references projects(id),
  agent_session_id uuid references agent_sessions(id),
  policy_bundle_id uuid not null references policy_bundles(id),
  policy_evaluation_id uuid not null references policy_evaluations(id),
  decision_stage varchar(40) not null,
  request_fingerprint varchar(64) not null,
  decision varchar(40) not null,
  reason_code varchar(160) not null,
  context_sha256 varchar(64) not null,
  decided_by varchar(240) not null,
  decided_at timestamptz not null default now(),
  unique(project_id, decision_stage, request_fingerprint)
);
create index runtime_ai_decisions_project_decided_idx on runtime_ai_decisions(project_id, decided_at desc);
