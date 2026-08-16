create table prompt_templates (
  id uuid primary key,
  project_id uuid not null references projects(id),
  template_key varchar(160) not null,
  semantic_version varchar(80) not null,
  display_name varchar(240) not null,
  source_reference varchar(2000),
  template_sha256 varchar(64) not null,
  classification varchar(80) not null default 'INTERNAL',
  registered_by varchar(200) not null,
  registered_at timestamptz not null default current_timestamp,
  constraint chk_prompt_template_key check (template_key ~ '^[a-z0-9._-]{3,160}$'),
  constraint chk_prompt_template_semver check (semantic_version ~ '^[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.-]+)?$'),
  constraint chk_prompt_template_digest check (template_sha256 ~ '^[a-f0-9]{64}$'),
  unique(project_id, template_key, semantic_version)
);
create index idx_prompt_templates_project_registered on prompt_templates(project_id, registered_at desc);

create table agent_sessions (
  id uuid primary key,
  project_id uuid not null references projects(id),
  prompt_template_id uuid references prompt_templates(id),
  agent_identity varchar(240) not null,
  provider varchar(160) not null,
  model_name varchar(240) not null,
  model_version varchar(240) not null,
  session_fingerprint varchar(64) not null,
  context_sha256 varchar(64),
  tool_invocation_count integer not null default 0,
  tool_invocation_sha256 varchar(64),
  purpose varchar(2000),
  status varchar(30) not null default 'DECLARED',
  declared_by varchar(200) not null,
  declared_at timestamptz not null default current_timestamp,
  completed_at timestamptz,
  constraint chk_agent_session_fingerprint check (session_fingerprint ~ '^[a-f0-9]{64}$'),
  constraint chk_agent_session_context_digest check (context_sha256 is null or context_sha256 ~ '^[a-f0-9]{64}$'),
  constraint chk_agent_session_tools_digest check (tool_invocation_sha256 is null or tool_invocation_sha256 ~ '^[a-f0-9]{64}$'),
  constraint chk_agent_session_tool_count check (tool_invocation_count >= 0 and tool_invocation_count <= 100000),
  constraint chk_agent_session_status check (status in ('DECLARED', 'COMPLETED', 'BLOCKED')),
  unique(project_id, session_fingerprint)
);
create index idx_agent_sessions_project_declared on agent_sessions(project_id, declared_at desc);
create index idx_agent_sessions_template on agent_sessions(prompt_template_id) where prompt_template_id is not null;

create table agent_evidence (
  id uuid primary key,
  project_id uuid not null references projects(id),
  agent_session_id uuid not null references agent_sessions(id),
  validation_run_id uuid references validation_runs(id),
  evidence_asset_id uuid references evidence_assets(id),
  approval_request_id uuid not null references approval_requests(id),
  change_reference varchar(2000) not null,
  generated_change_sha256 varchar(64) not null,
  policy_decision varchar(30) not null,
  policy_reference varchar(2000),
  declared_by varchar(200) not null,
  declared_at timestamptz not null default current_timestamp,
  constraint chk_agent_evidence_change_digest check (generated_change_sha256 ~ '^[a-f0-9]{64}$'),
  constraint chk_agent_evidence_policy_decision check (policy_decision in ('PASS', 'FAIL', 'NOT_EVALUATED')),
  unique(agent_session_id, generated_change_sha256)
);
create index idx_agent_evidence_project_declared on agent_evidence(project_id, declared_at desc);
create index idx_agent_evidence_approval on agent_evidence(approval_request_id);
