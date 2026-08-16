alter table runtime_ai_provider_profiles
  add column endpoint_uri varchar(2048),
  add column require_mtls boolean not null default false,
  add column mtls_reference varchar(240);

alter table runtime_ai_provider_profiles
  add constraint runtime_ai_provider_profiles_mtls_reference_ck
  check (not require_mtls or mtls_reference is not null);

create table runtime_ai_provider_dispatches (
  id uuid primary key,
  project_id uuid not null references projects(id),
  provider_profile_id uuid not null references runtime_ai_provider_profiles(id),
  runtime_decision_id uuid,
  agent_session_id uuid not null,
  idempotency_key uuid not null,
  request_fingerprint varchar(64) not null,
  request_sha256 varchar(64) not null,
  response_sha256 varchar(64),
  dispatch_status varchar(32) not null check (dispatch_status in ('IN_FLIGHT','COMPLETE','FAILED')),
  reason_code varchar(160) not null,
  http_status integer,
  attempts smallint not null check (attempts between 0 and 3),
  created_at timestamptz not null default now(),
  completed_at timestamptz,
  unique(project_id, idempotency_key)
);

create index runtime_ai_provider_dispatches_project_created_idx
  on runtime_ai_provider_dispatches(project_id, created_at desc);
