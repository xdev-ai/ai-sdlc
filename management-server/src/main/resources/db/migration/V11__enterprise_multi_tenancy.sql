create table tenants (
  id uuid primary key,
  slug varchar(80) not null unique,
  display_name varchar(160) not null,
  tenant_status varchar(30) not null,
  data_residency varchar(80) not null,
  encryption_key_reference varchar(300),
  legal_hold_enabled boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

insert into tenants (id, slug, display_name, tenant_status, data_residency)
values ('00000000-0000-0000-0000-000000000001', 'default', 'Default tenant', 'ACTIVE', 'GLOBAL')
on conflict (slug) do nothing;

alter table organizations add column tenant_id uuid;
update organizations set tenant_id = '00000000-0000-0000-0000-000000000001' where tenant_id is null;
alter table organizations alter column tenant_id set not null;
alter table organizations add constraint organizations_tenant_fk foreign key (tenant_id) references tenants(id);
create index organizations_tenant_idx on organizations(tenant_id, slug);

alter table projects add column tenant_id uuid;
update projects p set tenant_id = o.tenant_id from organizations o where p.organization_id = o.id and p.tenant_id is null;
alter table projects alter column tenant_id set not null;
alter table projects add constraint projects_tenant_fk foreign key (tenant_id) references tenants(id);
create index projects_tenant_idx on projects(tenant_id, organization_id);

alter table project_memberships add column tenant_id uuid;
update project_memberships m set tenant_id = p.tenant_id from projects p where m.project_id = p.id and m.tenant_id is null;
alter table project_memberships alter column tenant_id set not null;
alter table project_memberships add constraint project_memberships_tenant_fk foreign key (tenant_id) references tenants(id);

alter table spec_kits add column tenant_id uuid;
update spec_kits x set tenant_id = o.tenant_id from organizations o where x.organization_id = o.id and x.tenant_id is null;
alter table constitutions add column tenant_id uuid;
update constitutions x set tenant_id = o.tenant_id from organizations o where x.organization_id = o.id and x.tenant_id is null;
alter table policies add column tenant_id uuid;
update policies x set tenant_id = o.tenant_id from organizations o where x.organization_id = o.id and x.tenant_id is null;
alter table capability_grants add column tenant_id uuid;
update capability_grants x set tenant_id = o.tenant_id from organizations o where x.organization_id = o.id and x.tenant_id is null;
alter table exception_requests add column tenant_id uuid;
update exception_requests x set tenant_id = o.tenant_id from organizations o where x.organization_id = o.id and x.tenant_id is null;

alter table project_kits add column tenant_id uuid;
update project_kits x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table validation_runs add column tenant_id uuid;
update validation_runs x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table findings add column tenant_id uuid;
update findings x set tenant_id = r.tenant_id from validation_runs r where x.validation_run_id = r.id and x.tenant_id is null;
alter table validation_evidences add column tenant_id uuid;
update validation_evidences x set tenant_id = r.tenant_id from validation_runs r where x.validation_run_id = r.id and x.tenant_id is null;
alter table trace_nodes add column tenant_id uuid;
update trace_nodes x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table trace_edges add column tenant_id uuid;
update trace_edges x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table review_items add column tenant_id uuid;
update review_items x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table quality_metric_snapshots add column tenant_id uuid;
update quality_metric_snapshots x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;

alter table evidence_assets add column tenant_id uuid;
update evidence_assets x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table scm_repository_links add column tenant_id uuid;
update scm_repository_links x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table scm_events add column tenant_id uuid;
update scm_events x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table notification_channels add column tenant_id uuid;
update notification_channels x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table notification_deliveries add column tenant_id uuid;
update notification_deliveries x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table approval_requests add column tenant_id uuid;
update approval_requests x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table security_exception_notices add column tenant_id uuid;
update security_exception_notices x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table sbom_assets add column tenant_id uuid;
update sbom_assets x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table provenance_records add column tenant_id uuid;
update provenance_records x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table policy_bundles add column tenant_id uuid;
update policy_bundles x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table policy_evaluations add column tenant_id uuid;
update policy_evaluations x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table prompt_templates add column tenant_id uuid;
update prompt_templates x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table agent_sessions add column tenant_id uuid;
update agent_sessions x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table agent_evidence add column tenant_id uuid;
update agent_evidence x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;
alter table risk_scores add column tenant_id uuid;
update risk_scores x set tenant_id = p.tenant_id from projects p where x.project_id = p.id and x.tenant_id is null;

alter table audit_events disable trigger audit_events_no_update;
alter table audit_events add column tenant_id uuid;
update audit_events x set tenant_id = o.tenant_id from organizations o where x.organization_id = o.id and x.tenant_id is null;
alter table audit_events alter column tenant_id set not null;
alter table audit_events add constraint audit_events_tenant_fk foreign key (tenant_id) references tenants(id);
create index audit_events_tenant_idx on audit_events(tenant_id, occurred_at desc);
alter table audit_events enable trigger audit_events_no_update;

create table tenant_memberships (
  id uuid primary key, tenant_id uuid not null references tenants(id), subject varchar(200) not null,
  tenant_role varchar(40) not null, created_at timestamptz not null default now(),
  unique(tenant_id, subject)
);
create table tenant_permission_sets (
  id uuid primary key, tenant_id uuid not null references tenants(id), permission_key varchar(100) not null,
  display_name varchar(160) not null, permissions_json jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(), unique(tenant_id, permission_key)
);
create table tenant_permission_assignments (
  id uuid primary key, tenant_id uuid not null references tenants(id), subject varchar(200) not null,
  permission_set_id uuid not null references tenant_permission_sets(id), assigned_by varchar(200) not null,
  created_at timestamptz not null default now(), unique(tenant_id, subject, permission_set_id)
);
create table tenant_federation_configs (
  id uuid primary key, tenant_id uuid not null references tenants(id), protocol varchar(20) not null,
  issuer_uri varchar(500) not null, client_id varchar(300), client_secret_ciphertext text,
  metadata_uri varchar(500), claim_mapping_json jsonb not null default '{}'::jsonb, enabled boolean not null default false,
  created_by varchar(200) not null, created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  unique(tenant_id, protocol, issuer_uri)
);
create table scim_service_principals (
  id uuid primary key, tenant_id uuid not null references tenants(id), display_name varchar(160) not null,
  token_sha256 char(64) not null unique, active boolean not null default true,
  created_by varchar(200) not null, created_at timestamptz not null default now(), revoked_at timestamptz
);
create table scim_users (
  id uuid primary key, tenant_id uuid not null references tenants(id), external_id varchar(200), subject varchar(200) not null,
  user_name varchar(300) not null, display_name varchar(300), active boolean not null default true,
  attributes_json jsonb not null default '{}'::jsonb, created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  unique(tenant_id, subject), unique(tenant_id, user_name)
);
create table tenant_legal_holds (
  id uuid primary key, tenant_id uuid not null references tenants(id), hold_key varchar(120) not null,
  reason text not null, active boolean not null default true, created_by varchar(200) not null,
  released_by varchar(200), created_at timestamptz not null default now(), released_at timestamptz, unique(tenant_id, hold_key)
);
create table e_discovery_exports (
  id uuid primary key, tenant_id uuid not null references tenants(id), requested_by varchar(200) not null,
  scope_json jsonb not null, export_status varchar(30) not null, object_bucket varchar(160), object_key varchar(500),
  manifest_sha256 char(64), size_bytes bigint, retention_until timestamptz, created_at timestamptz not null default now(), ready_at timestamptz
);
create index e_discovery_exports_tenant_created_idx on e_discovery_exports(tenant_id, created_at desc);
create table tenant_audit_events (
  id uuid primary key, tenant_id uuid not null references tenants(id), actor_subject varchar(200) not null,
  action varchar(160) not null, entity_type varchar(100) not null, entity_id varchar(160), payload jsonb,
  occurred_at timestamptz not null default now()
);
create index tenant_audit_events_tenant_idx on tenant_audit_events(tenant_id, occurred_at desc);
create or replace function block_tenant_audit_event_mutation() returns trigger as $$ begin raise exception 'tenant_audit_events is append-only'; end; $$ language plpgsql;
create trigger tenant_audit_events_no_update before update on tenant_audit_events for each row execute function block_tenant_audit_event_mutation();
create trigger tenant_audit_events_no_delete before delete on tenant_audit_events for each row execute function block_tenant_audit_event_mutation();
