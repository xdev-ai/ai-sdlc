create table sbom_assets (
  id uuid primary key,
  project_id uuid not null references projects(id),
  evidence_asset_id uuid not null unique references evidence_assets(id),
  sbom_format varchar(20) not null,
  spec_version varchar(40),
  serial_number varchar(500),
  component_count integer not null,
  release_reference varchar(200),
  document_sha256 varchar(64) not null,
  ingested_by varchar(200) not null,
  ingested_at timestamptz not null default current_timestamp,
  constraint chk_sbom_digest check (document_sha256 ~ '^[a-f0-9]{64}$'),
  constraint chk_sbom_component_count check (component_count >= 0),
  unique(project_id, document_sha256)
);
create index idx_sbom_assets_project_ingested on sbom_assets(project_id, ingested_at desc);
create index idx_sbom_assets_release_reference on sbom_assets(project_id, release_reference);

create table provenance_records (
  id uuid primary key,
  project_id uuid not null references projects(id),
  sbom_asset_id uuid references sbom_assets(id),
  attestation_evidence_asset_id uuid references evidence_assets(id),
  artifact_name varchar(300) not null,
  artifact_digest varchar(71) not null,
  source_repository varchar(500) not null,
  source_revision varchar(128) not null,
  build_system varchar(120) not null,
  build_url varchar(2000),
  signer_identity varchar(500) not null,
  signature_method varchar(40) not null,
  attestation_reference varchar(2000),
  verification_status varchar(20) not null default 'DECLARED',
  verified_by varchar(200),
  verified_at timestamptz,
  verification_note text,
  created_by varchar(200) not null,
  created_at timestamptz not null default current_timestamp,
  constraint chk_provenance_digest check (artifact_digest ~ '^sha256:[a-f0-9]{64}$')
);
create index idx_provenance_project_created on provenance_records(project_id, created_at desc);
create index idx_provenance_artifact_digest on provenance_records(artifact_digest);
