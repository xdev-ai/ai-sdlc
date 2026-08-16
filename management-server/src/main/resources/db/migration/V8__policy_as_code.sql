create table policy_bundles (
  id uuid primary key,
  project_id uuid not null references projects(id),
  bundle_key varchar(160) not null,
  semantic_version varchar(80) not null,
  description varchar(2000),
  cel_expression text not null,
  source_sha256 varchar(64) not null,
  fixture_json jsonb not null default '[]'::jsonb,
  dry_run_default boolean not null default true,
  lifecycle_status varchar(20) not null default 'DRAFT',
  compilation_error text,
  checked_at timestamptz,
  activated_at timestamptz,
  activated_by varchar(200),
  retired_at timestamptz,
  retired_by varchar(200),
  created_by varchar(200) not null,
  created_at timestamptz not null default current_timestamp,
  constraint chk_policy_bundle_key check (bundle_key ~ '^[a-z0-9._-]{3,160}$'),
  constraint chk_policy_bundle_semver check (semantic_version ~ '^[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.-]+)?$'),
  constraint chk_policy_bundle_source_digest check (source_sha256 ~ '^[a-f0-9]{64}$'),
  constraint chk_policy_bundle_expression_length check (char_length(cel_expression) between 1 and 12000),
  constraint chk_policy_bundle_lifecycle check (lifecycle_status in ('DRAFT', 'ACTIVE', 'RETIRED')),
  unique(project_id, bundle_key, semantic_version)
);
create unique index uq_active_policy_bundle_key on policy_bundles(project_id, bundle_key) where lifecycle_status = 'ACTIVE';
create index idx_policy_bundles_project_created on policy_bundles(project_id, created_at desc);

create table policy_evaluations (
  id uuid primary key,
  policy_bundle_id uuid not null references policy_bundles(id),
  project_id uuid not null references projects(id),
  context_sha256 varchar(64) not null,
  evaluation_mode varchar(20) not null,
  outcome varchar(20) not null,
  result boolean,
  error_code varchar(120),
  detail jsonb not null default '{}'::jsonb,
  evaluated_by varchar(200) not null,
  evaluated_at timestamptz not null default current_timestamp,
  constraint chk_policy_evaluation_context_digest check (context_sha256 ~ '^[a-f0-9]{64}$'),
  constraint chk_policy_evaluation_mode check (evaluation_mode in ('DRY_RUN', 'ENFORCEMENT', 'FIXTURE')),
  constraint chk_policy_evaluation_outcome check (outcome in ('PASS', 'FAIL', 'ERROR')),
  constraint chk_policy_evaluation_result check ((outcome = 'ERROR' and result is null) or (outcome <> 'ERROR' and result is not null))
);
create index idx_policy_evaluations_bundle_time on policy_evaluations(policy_bundle_id, evaluated_at desc);
create index idx_policy_evaluations_project_time on policy_evaluations(project_id, evaluated_at desc);
