create table notification_channels (
  id uuid primary key, project_id uuid not null references projects(id), channel_type varchar(30) not null,
  name varchar(120) not null, destination_ciphertext text not null, secret_ciphertext text,
  enabled boolean not null default true, created_by varchar(200) not null, created_at timestamptz not null,
  updated_at timestamptz not null, unique(project_id, channel_type, name)
);
create index notification_channels_project_enabled_idx on notification_channels(project_id, enabled);

create table notification_deliveries (
  id uuid primary key, project_id uuid not null references projects(id), channel_id uuid not null references notification_channels(id),
  event_type varchar(120) not null, subject varchar(300) not null, body text not null, idempotency_key varchar(180) not null,
  payload_sha256 char(64) not null, recipient_fingerprint char(64) not null, delivery_status varchar(30) not null,
  attempts integer not null default 0, next_attempt_at timestamptz not null, last_attempt_at timestamptz,
  delivered_at timestamptz, terminal_error_code varchar(120), version bigint not null default 0,
  created_at timestamptz not null, updated_at timestamptz not null, unique(channel_id, idempotency_key)
);
create index notification_deliveries_dispatch_idx on notification_deliveries(delivery_status, next_attempt_at);
create index notification_deliveries_project_created_idx on notification_deliveries(project_id, created_at desc);

create table notification_delivery_receipts (
  id uuid primary key, delivery_id uuid not null references notification_deliveries(id), attempt_number integer not null,
  outcome varchar(30) not null, http_status integer, error_code varchar(120), payload_sha256 char(64) not null,
  delivery_timestamp timestamptz not null, unique(delivery_id, attempt_number)
);
create index notification_delivery_receipts_delivery_idx on notification_delivery_receipts(delivery_id, delivery_timestamp desc);

create table approval_requests (
  id uuid primary key, project_id uuid not null references projects(id), source_type varchar(80) not null, source_id varchar(200),
  title varchar(300) not null, details text, approval_status varchar(30) not null, required_quorum integer not null,
  requested_approver_subject varchar(200), delegated_approver_subject varchar(200), delegated_by varchar(200),
  created_by varchar(200) not null, due_at timestamptz not null, last_reminder_at timestamptz, escalated_at timestamptz,
  decided_at timestamptz, created_at timestamptz not null, updated_at timestamptz not null,
  constraint approval_requests_quorum_ck check (required_quorum > 0)
);
create index approval_requests_project_status_due_idx on approval_requests(project_id, approval_status, due_at);
create index approval_requests_sla_idx on approval_requests(approval_status, due_at);

create table approval_decisions (
  id uuid primary key, approval_request_id uuid not null references approval_requests(id), actor varchar(200) not null,
  decision varchar(30) not null, comment text, decided_at timestamptz not null,
  unique(approval_request_id, actor)
);
create index approval_decisions_request_idx on approval_decisions(approval_request_id, decided_at);

create table security_exception_notices (
  id uuid primary key, project_id uuid not null references projects(id), source_reference varchar(300) not null,
  justification text not null, exception_status varchar(30) not null, expires_at timestamptz not null,
  last_reminder_at timestamptz, expired_at timestamptz, created_by varchar(200) not null,
  created_at timestamptz not null, updated_at timestamptz not null,
  unique(project_id, source_reference)
);
create index security_exception_notices_expiry_idx on security_exception_notices(exception_status, expires_at);
