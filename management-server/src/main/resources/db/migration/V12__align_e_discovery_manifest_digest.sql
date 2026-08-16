-- Keep the published V11 migration immutable. Hibernate maps the nullable digest as varchar(64),
-- so align the PostgreSQL column type for fresh and existing v11 databases.
alter table e_discovery_exports
  alter column manifest_sha256 type varchar(64);
