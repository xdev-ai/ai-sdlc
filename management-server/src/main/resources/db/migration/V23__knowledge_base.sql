-- A Confluence-shaped knowledge base: spaces, nested pages, immutable page versions, labels, and retrieval chunks.
--
-- Why a new structure rather than reusing spec_kits. A Spec Kit is a *released artifact* — one immutable manifest,
-- registered and pinned, deliberately hard to change. Project documentation is the opposite shape: authored prose
-- that is edited continuously, arranged in a tree someone browses, and read a paragraph at a time. Forcing pages
-- into spec_kits would make every typo a release, and forcing releases into pages would lose the pinning contract.
-- They are linked instead, through knowledge_page_references.
--
-- What "so AI can understand it" means concretely here. Page bodies are stored as Markdown, because that is what a
-- model reads without a converter, and each version is split into knowledge_chunks carrying the heading path that
-- leads to them. A chunk is the unit handed to a model: small enough to fit a prompt, and labelled with where it sits
-- in the document so an answer can cite a section rather than a whole file.
--
-- Retrieval is lexical, not semantic. pgvector is not available in this PostgreSQL image, so there are no embeddings
-- and no similarity search; there is unaccent + pg_trgm and a `simple` tsvector. PostgreSQL also ships no Vietnamese
-- text-search configuration, so Vietnamese content gets `simple` — exact and prefix matching after accent folding,
-- with no stemming. This is keyword retrieval. Calling it semantic search would be a lie, and a column named
-- `embedding` that nothing can populate would be a worse one.

create extension if not exists unaccent;
create extension if not exists pg_trgm;

-- unaccent() is declared STABLE, not IMMUTABLE, because it reads a dictionary that an administrator could change.
-- PostgreSQL therefore refuses it inside a generated column: "generation expression is not immutable". The
-- documented workaround is to wrap the two-argument form, which names its dictionary explicitly, in an IMMUTABLE
-- function.
--
-- The honest cost: if the unaccent dictionary is ever modified, existing search_vector values and the index built
-- over them become stale, and the fix is to rebuild them. Accent folding is worth that for Vietnamese content —
-- someone searching "tiep nhan" must find "tiếp nhận" — and the alternative is a search that only matches text typed
-- with identical diacritics.
create or replace function immutable_unaccent(text) returns text
  language sql immutable strict parallel safe
  as $$ select public.unaccent('public.unaccent'::regdictionary, $1) $$;

-- A space is the top-level container a person browses, scoped to an organization and optionally narrowed to one
-- project. Tenant scope is carried explicitly, as everywhere else in this schema.
create table knowledge_spaces (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  organization_id uuid not null references organizations(id),
  project_id uuid references projects(id),
  space_key varchar(60) not null,
  name varchar(200) not null,
  description text,
  created_by varchar(240) not null,
  created_at timestamptz not null default now(),
  archived_at timestamptz,
  archived_by varchar(240),
  constraint knowledge_space_key_uq unique (organization_id, space_key),
  constraint knowledge_space_key_ck check (space_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{1,59}$'),
  constraint knowledge_space_archive_ck
    check ((archived_at is null and archived_by is null) or (archived_at is not null and archived_by is not null))
);

-- A page is a node in the space tree. Its text lives in knowledge_page_versions; this row holds identity, position
-- and which version is current.
create table knowledge_pages (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  space_id uuid not null references knowledge_spaces(id),
  parent_page_id uuid references knowledge_pages(id),
  slug varchar(160) not null,
  current_version integer not null default 0,
  position integer not null default 0,
  page_status varchar(16) not null default 'DRAFT'
    check (page_status in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
  created_by varchar(240) not null,
  created_at timestamptz not null default now(),
  constraint knowledge_page_slug_uq unique (space_id, slug),
  constraint knowledge_page_slug_ck check (slug ~ '^[a-z0-9][a-z0-9-]{1,159}$'),
  constraint knowledge_page_not_own_parent_ck check (parent_page_id is distinct from id)
);

create index knowledge_page_tree_idx on knowledge_pages (space_id, parent_page_id, position);
create index knowledge_page_status_idx on knowledge_pages (space_id, page_status);

-- Every edit is a new row. Nothing here is ever updated: the whole point of keeping documentation in a governed
-- system rather than a shared drive is that the previous wording is still retrievable and attributable.
create table knowledge_page_versions (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  page_id uuid not null references knowledge_pages(id),
  version integer not null,
  title varchar(300) not null,
  -- Markdown, because a model reads it directly and a human can diff it.
  body text not null,
  body_sha256 varchar(64) not null,
  -- Why this edit happened. Confluence calls it a version comment; an audit trail calls it the reason.
  change_note varchar(1000),
  authored_by varchar(240) not null,
  authored_at timestamptz not null default now(),
  constraint knowledge_page_version_uq unique (page_id, version),
  constraint knowledge_page_version_positive_ck check (version >= 1),
  constraint knowledge_page_body_digest_ck check (body_sha256 ~ '^[a-f0-9]{64}$')
);

create index knowledge_page_version_recent_idx on knowledge_page_versions (page_id, version desc);

create table knowledge_page_labels (
  page_id uuid not null references knowledge_pages(id),
  label varchar(80) not null,
  applied_by varchar(240) not null,
  applied_at timestamptz not null default now(),
  primary key (page_id, label),
  constraint knowledge_label_ck check (label ~ '^[a-z0-9][a-z0-9._-]{0,79}$')
);

create index knowledge_label_lookup_idx on knowledge_page_labels (label);

-- A page cites the governed artifacts it describes, so documentation and evidence do not drift apart. Exactly one
-- target per row, which the check enforces rather than trusting the caller.
create table knowledge_page_references (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  page_id uuid not null references knowledge_pages(id),
  spec_kit_id uuid references spec_kits(id),
  trace_node_id uuid references trace_nodes(id),
  evidence_asset_id uuid references evidence_assets(id),
  reference_note varchar(500),
  linked_by varchar(240) not null,
  linked_at timestamptz not null default now(),
  constraint knowledge_reference_one_target_ck check (
    (spec_kit_id is not null)::int + (trace_node_id is not null)::int + (evidence_asset_id is not null)::int = 1)
);

create index knowledge_reference_page_idx on knowledge_page_references (page_id);
create index knowledge_reference_kit_idx on knowledge_page_references (spec_kit_id) where spec_kit_id is not null;
create index knowledge_reference_node_idx on knowledge_page_references (trace_node_id) where trace_node_id is not null;

-- The retrieval unit. One row per contiguous section of one page version, carrying the heading path that leads to it
-- so a model can cite "Tiếp nhận > Nhập thông tin" instead of a file name. Chunks belong to a version, so they are
-- as immutable as the version and are rebuilt when a new version is authored.
create table knowledge_chunks (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  page_version_id uuid not null references knowledge_page_versions(id),
  ordinal integer not null,
  heading_path varchar(600) not null,
  content text not null,
  content_sha256 varchar(64) not null,
  -- `simple` rather than a language configuration: PostgreSQL ships no Vietnamese stemmer, and using `english` on
  -- Vietnamese text would stem the wrong language while claiming to help.
  search_vector tsvector generated always as (
    to_tsvector('simple', immutable_unaccent(coalesce(heading_path, '') || ' ' || coalesce(content, '')))) stored,
  constraint knowledge_chunk_ordinal_uq unique (page_version_id, ordinal),
  constraint knowledge_chunk_ordinal_ck check (ordinal >= 0),
  constraint knowledge_chunk_digest_ck check (content_sha256 ~ '^[a-f0-9]{64}$')
);

create index knowledge_chunk_search_idx on knowledge_chunks using gin (search_vector);
create index knowledge_chunk_trigram_idx on knowledge_chunks using gin (content gin_trgm_ops);
create index knowledge_chunk_version_idx on knowledge_chunks (page_version_id, ordinal);

-- Versions and chunks are append-only, for the same reason spec_kits and the audit ledger are: an edited version is
-- not a version, and a rewritten chunk silently changes what an AI answer was grounded in.
create or replace function block_knowledge_version_mutation() returns trigger as $$
begin
  raise exception 'knowledge_page_versions is append-only; author a new version instead of editing version % of page %',
    old.version, old.page_id;
end;
$$ language plpgsql;

create trigger knowledge_page_versions_no_update before update on knowledge_page_versions
  for each row execute function block_knowledge_version_mutation();
create trigger knowledge_page_versions_no_delete before delete on knowledge_page_versions
  for each row execute function block_knowledge_version_mutation();

-- A page tree must stay a tree. Without this a parent could be pointed at one of its own descendants, and every
-- recursive read — breadcrumbs, subtree export, the AI's own context assembly — would loop forever.
create or replace function block_knowledge_page_cycle() returns trigger as $$
declare
  ancestor uuid := new.parent_page_id;
  parent_space uuid;
  hops integer := 0;
begin
  if new.parent_page_id is null then
    return new;
  end if;

  select space_id into parent_space from knowledge_pages where id = new.parent_page_id;
  if parent_space is distinct from new.space_id then
    raise exception 'a knowledge page may only be nested under a page in the same space';
  end if;

  while ancestor is not null loop
    if ancestor = new.id then
      raise exception 'knowledge page hierarchy would form a cycle at page %', new.id;
    end if;
    hops := hops + 1;
    if hops > 100 then
      raise exception 'knowledge page hierarchy exceeds 100 levels; refusing to walk further';
    end if;
    select parent_page_id into ancestor from knowledge_pages where id = ancestor;
  end loop;
  return new;
end;
$$ language plpgsql;

create trigger knowledge_pages_no_cycle before insert or update of parent_page_id on knowledge_pages
  for each row execute function block_knowledge_page_cycle();

comment on table knowledge_spaces is 'Top-level documentation container, scoped to an organization and optionally one project.';
comment on table knowledge_pages is 'A node in a space tree. Text lives in knowledge_page_versions; current_version names the live one.';
comment on table knowledge_page_versions is 'Append-only page history. Every edit is a new version with an author and a reason.';
comment on table knowledge_chunks is 'Retrieval units for AI context assembly: one section of one page version, with its heading path. Lexical search only — no embeddings, because pgvector is unavailable.';
