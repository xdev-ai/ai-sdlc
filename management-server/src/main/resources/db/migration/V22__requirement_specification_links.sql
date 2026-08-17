-- Link a requirement to the document version that specifies it.
--
-- The traceability graph records requirement → spec → task → test → evidence, and spec_kits records immutable
-- document versions, but nothing connected the two. A requirement could not answer "which version of which analysis
-- document specifies me", which is the column an external requirement sheet carries as its analysis-document column
-- (for example SPEC-042_v1.0 against screen SCR-042).
--
-- Deliberately a table rather than a spec_kit_id column on trace_nodes. A column would be overwritten every time a
-- requirement is re-specified by a newer document, which destroys the one fact this is being built for: when the
-- governing document changed, who changed it, and what it was before. The whole point of document change management
-- is the history, so the history is the schema.
--
-- A requirement may have at most one *current* link, enforced by a partial unique index. Superseding is a two-step
-- the application performs in one transaction: close the current row, insert the new one. Rows are never deleted.

create table requirement_specifications (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  project_id uuid not null references projects(id),
  trace_node_id uuid not null references trace_nodes(id),
  spec_kit_id uuid not null references spec_kits(id),
  -- The document code exactly as the source system writes it, case intact. spec_kits.slug is constrained to
  -- [a-z0-9-], so DOC-001 and SPEC-042_v1.0 cannot round-trip through it. Losing the original
  -- reference would break the link back to the authority that issued the document.
  source_document_code varchar(300) not null,
  linked_by varchar(240) not null,
  linked_at timestamptz not null default now(),
  superseded_by varchar(240),
  superseded_at timestamptz,
  supersede_reason text,
  constraint requirement_specification_supersede_ck
    check ((superseded_at is null and superseded_by is null)
        or (superseded_at is not null and superseded_by is not null)),
  constraint requirement_specification_order_ck
    check (superseded_at is null or superseded_at >= linked_at)
);

-- At most one current specification per requirement. A superseded row leaves the index, so history accumulates
-- freely while the present stays unambiguous.
create unique index requirement_specification_current_uq
  on requirement_specifications (trace_node_id) where superseded_at is null;

create index requirement_specification_node_idx on requirement_specifications (trace_node_id, linked_at desc);
create index requirement_specification_kit_idx on requirement_specifications (spec_kit_id);
create index requirement_specification_project_idx on requirement_specifications (project_id, linked_at desc);

-- Append-only, for the same reason spec_kits is: deleting a link erases the record of what a past release was
-- specified against. Closing a link is an UPDATE of the supersede columns, so UPDATE stays permitted, but only
-- those columns may move.
create or replace function block_requirement_specification_rewrite() returns trigger as $$
begin
  if new.tenant_id is distinct from old.tenant_id
     or new.project_id           is distinct from old.project_id
     or new.trace_node_id        is distinct from old.trace_node_id
     or new.spec_kit_id          is distinct from old.spec_kit_id
     or new.source_document_code is distinct from old.source_document_code
     or new.linked_by            is distinct from old.linked_by
     or new.linked_at            is distinct from old.linked_at then
    raise exception
      'requirement_specifications links are immutable; supersede the link instead of rewriting it (node %, kit %)',
      old.trace_node_id, old.spec_kit_id;
  end if;
  return new;
end;
$$ language plpgsql;

create trigger requirement_specifications_no_rewrite before update on requirement_specifications
  for each row execute function block_requirement_specification_rewrite();

create or replace function block_requirement_specification_delete() returns trigger as $$
begin
  raise exception 'requirement_specifications is append-only; supersede the link instead of deleting it';
end;
$$ language plpgsql;

create trigger requirement_specifications_no_delete before delete on requirement_specifications
  for each row execute function block_requirement_specification_delete();

comment on table requirement_specifications is
  'Which immutable document version specifies which requirement, and the full history of that assignment. One current link per requirement; superseded links are retained.';
