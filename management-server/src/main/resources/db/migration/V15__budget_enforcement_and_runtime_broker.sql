create table inference_budget_policies (
  id uuid primary key,
  project_id uuid not null unique references projects(id),
  currency_code char(3) not null,
  calendar_month_limit_minor bigint not null check (calendar_month_limit_minor > 0),
  warning_percent smallint not null check (warning_percent between 1 and 99),
  enforcement_mode varchar(32) not null check (enforcement_mode in ('ADVISORY','HOLD')),
  active boolean not null default true,
  created_by varchar(240) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table inference_budget_decisions (
  id uuid primary key,
  budget_policy_id uuid references inference_budget_policies(id),
  project_id uuid not null references projects(id),
  period_start date not null,
  currency_code char(3) not null,
  spent_minor bigint not null check (spent_minor >= 0),
  limit_minor bigint not null check (limit_minor > 0),
  decision varchar(32) not null check (decision in ('ALLOW','WARN','HOLD','EXCEPTION_APPROVED','DENY_NO_POLICY')),
  reason_code varchar(160) not null,
  evidence_sha256 varchar(64) not null,
  decided_by varchar(240) not null,
  decided_at timestamptz not null default now()
);
create index inference_budget_decisions_project_period_idx on inference_budget_decisions(project_id, period_start desc);
create table inference_budget_exceptions (
  id uuid primary key,
  budget_policy_id uuid not null references inference_budget_policies(id),
  project_id uuid not null references projects(id),
  approval_request_id uuid not null references approval_requests(id),
  requested_by varchar(240) not null,
  requested_at timestamptz not null default now(),
  expires_at timestamptz not null,
  rationale_sha256 varchar(64) not null,
  unique(budget_policy_id, approval_request_id)
);
create index inference_budget_exceptions_active_idx on inference_budget_exceptions(project_id, expires_at desc);

create table runtime_ai_workload_identities (
  id uuid primary key,
  project_id uuid not null references projects(id),
  workload_subject varchar(240) not null,
  active boolean not null default true,
  created_by varchar(240) not null,
  created_at timestamptz not null default now(),
  unique(project_id, workload_subject)
);
create table runtime_ai_provider_profiles (
  id uuid primary key,
  project_id uuid not null references projects(id),
  provider_name varchar(160) not null,
  model_name varchar(240) not null,
  policy_bundle_id uuid not null references policy_bundles(id),
  credential_reference varchar(240) not null,
  timeout_ms integer not null check (timeout_ms between 100 and 120000),
  max_attempts smallint not null check (max_attempts between 1 and 3),
  active boolean not null default true,
  created_by varchar(240) not null,
  created_at timestamptz not null default now(),
  unique(project_id, provider_name, model_name)
);
create table runtime_ai_tool_capabilities (
  id uuid primary key,
  project_id uuid not null references projects(id),
  tool_name varchar(160) not null,
  policy_bundle_id uuid not null references policy_bundles(id),
  impact_level varchar(24) not null check (impact_level in ('READ_ONLY','MUTATING','HIGH_IMPACT')),
  requires_approval boolean not null default false,
  active boolean not null default true,
  created_by varchar(240) not null,
  created_at timestamptz not null default now(),
  unique(project_id, tool_name)
);
