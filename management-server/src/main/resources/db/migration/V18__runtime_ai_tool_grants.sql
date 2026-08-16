-- P3.3 tool broker: tenant-scoped, single-use capability grants.
-- The table stores digests and lifecycle state only. Raw tool arguments, prompts, model output, and
-- the grant secret itself are never written; only the SHA-256 of the grant nonce is retained.
create table runtime_ai_tool_grants (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  project_id uuid not null references projects(id),
  tool_capability_id uuid not null references runtime_ai_tool_capabilities(id),
  workload_subject varchar(240) not null,
  agent_session_id uuid not null,
  runtime_decision_id uuid,
  approval_request_id uuid references approval_requests(id),
  capability_scope varchar(24) not null check (capability_scope in ('READ_ONLY','MUTATING','HIGH_IMPACT')),
  tool_manifest_sha256 varchar(64) not null,
  argument_fingerprint varchar(64) not null,
  grant_nonce_sha256 varchar(64) not null,
  grant_status varchar(16) not null check (grant_status in ('ISSUED','REDEEMED','EXPIRED','REVOKED')),
  reason_code varchar(160) not null,
  issued_at timestamptz not null default now(),
  expires_at timestamptz not null,
  redeemed_at timestamptz,
  receipt_sha256 varchar(64),
  constraint runtime_ai_tool_grants_nonce_unique unique (grant_nonce_sha256),
  constraint runtime_ai_tool_grants_expiry_ck check (expires_at > issued_at),
  constraint runtime_ai_tool_grants_redeemed_ck
    check (grant_status <> 'REDEEMED' or (redeemed_at is not null and receipt_sha256 is not null)),
  constraint runtime_ai_tool_grants_high_impact_approval_ck
    check (capability_scope <> 'HIGH_IMPACT' or approval_request_id is not null)
);

create index runtime_ai_tool_grants_project_status_idx
  on runtime_ai_tool_grants(project_id, grant_status, expires_at desc);

create index runtime_ai_tool_grants_tenant_idx
  on runtime_ai_tool_grants(tenant_id, issued_at desc);
