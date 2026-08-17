-- Make a Spec Kit version immutable in the database, not merely by convention.
--
-- docs/architecture.md says "Register immutable versions". Until now that immutability rested on application code
-- never issuing the wrong UPDATE. The audit ledger, by contrast, is protected by an actual trigger. Those are two
-- different strengths of guarantee described as one, and for hospital or regulatory documentation the difference
-- matters: a version whose content can change is not a version.
--
-- UNIQUE (organization_id, slug, version, layer) already prevents a *duplicate* version. It does nothing to prevent
-- rewriting the manifest of an existing one, which would silently change what a pinned project is governed by while
-- the version number stayed the same.
--
-- Lifecycle still has to move, so this blocks identity and content only:
--   frozen  : organization_id, slug, version, layer, parent_kit_id, manifest, created_at
--   mutable : pinned, lifecycle_status, deprecated_at, deprecated_by, deprecation_reason, tenant_id
--
-- DELETE is blocked outright. A kit that should no longer be used is deprecated with a recorded reason and actor;
-- removing the row would erase the record of what a past release was validated against.

create or replace function block_spec_kit_version_rewrite() returns trigger as $$
begin
  if new.organization_id is distinct from old.organization_id
     or new.slug         is distinct from old.slug
     or new.version      is distinct from old.version
     or new.layer        is distinct from old.layer
     or new.parent_kit_id is distinct from old.parent_kit_id
     or new.manifest     is distinct from old.manifest
     or new.created_at   is distinct from old.created_at then
    raise exception
      'spec_kits version identity and manifest are immutable; register a new version instead of rewriting %/% (%)',
      old.slug, old.version, old.layer;
  end if;
  return new;
end;
$$ language plpgsql;

create trigger spec_kits_no_version_rewrite before update on spec_kits
  for each row execute function block_spec_kit_version_rewrite();

create or replace function block_spec_kit_delete() returns trigger as $$
begin
  raise exception 'spec_kits is append-only; deprecate the version with a recorded reason instead of deleting it';
end;
$$ language plpgsql;

create trigger spec_kits_no_delete before delete on spec_kits
  for each row execute function block_spec_kit_delete();

comment on trigger spec_kits_no_version_rewrite on spec_kits is
  'A registered Spec Kit version cannot have its identity or manifest rewritten. Lifecycle columns stay mutable so a version can be pinned, unpinned and deprecated.';
