#!/usr/bin/env bash
set -euo pipefail

# Recovery verification for the PostgreSQL governance ledger.
#
# docs/operations.md#production-operations-runbook documents how to back up and restore. A documented procedure that has never been
# executed is a claim, not a control: the specific failure it is supposed to prevent is discovering during an
# incident that the restore loses the audit hash chain, and no amount of prose detects that.
#
# What this proves, on a disposable database, without touching anything shared:
#
#   1. Flyway brings an empty database to the current schema version.
#   2. An append-only audit chain written into that database verifies.
#   3. pg_dump captures it and pg_restore reproduces it in a SEPARATE database.
#   4. The restored chain verifies to the SAME head digest — not merely "some valid chain", the same one.
#   5. Tampering with a restored row breaks verification, so step 4 is a real check and not a tautology.
#
# Step 5 is the one that matters. Without it, a verification routine that always returns "intact" would pass this
# script, and the whole exercise would certify nothing.
#
# Usage:  scripts/verify-recovery.sh
# Requires: docker, and the repository's Maven build for the migration set.

CONTAINER="aisdlc-recovery-check"
PORT="${AISDLC_RECOVERY_PORT:-55440}"
PGPASSWORD_VALUE="aisdlc_recovery_ephemeral"   # disposable container, destroyed on exit
SOURCE_DB="aisdlc_source"
RESTORED_DB="aisdlc_restored"
DUMP_FILE="$(mktemp -t aisdlc-recovery-XXXXXX).dump"
FAILURES=0

ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  rm -f "$DUMP_FILE"
}
trap cleanup EXIT

psql_source()   { docker exec -i "$CONTAINER" psql -U postgres -d "$SOURCE_DB" -tAX "$@"; }
psql_restored() { docker exec -i "$CONTAINER" psql -U postgres -d "$RESTORED_DB" -tAX "$@"; }

step "1  Start a disposable PostgreSQL"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --rm --name "$CONTAINER" \
  -e POSTGRES_PASSWORD="$PGPASSWORD_VALUE" -e POSTGRES_DB="$SOURCE_DB" \
  -p "${PORT}:5432" postgres:18.6-alpine >/dev/null
for _ in $(seq 1 40); do
  docker exec "$CONTAINER" pg_isready -U postgres -q 2>/dev/null && break
  sleep 2
done
docker exec "$CONTAINER" pg_isready -U postgres -q || { bad "PostgreSQL did not become ready"; exit 1; }
ok "PostgreSQL ${PORT} ready"

step "2  Migrate the source database to the current schema"
# The application does not exit after migrating — it is a server. Start it, wait for Flyway to finish, then stop it.
# Running the migration through the application rather than a standalone Flyway invocation is deliberate: it is the
# same code path production uses, including the ddl-auto=validate check that catches an entity drifting from its
# column definition.
mvn -B -q -pl management-server spring-boot:run \
  -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:${PORT}/${SOURCE_DB} --spring.datasource.username=postgres --spring.datasource.password=${PGPASSWORD_VALUE} --server.port=${MIGRATION_PORT:-18098} --aisdlc.telemetry.enabled=false" \
  > "${DUMP_FILE}.migrate.log" 2>&1 &
MIGRATOR_PID=$!
for _ in $(seq 1 90); do
  READY="$(psql_source -c "select count(*) from flyway_schema_history where success" 2>/dev/null || echo 0)"
  [ "${READY:-0}" -ge 20 ] && break
  kill -0 "$MIGRATOR_PID" 2>/dev/null || break
  sleep 2
done
kill "$MIGRATOR_PID" 2>/dev/null || true
wait "$MIGRATOR_PID" 2>/dev/null || true
APPLIED="$(psql_source -c "select count(*) from flyway_schema_history where success" 2>/dev/null || echo 0)"
FAILED="$(psql_source -c "select count(*) from flyway_schema_history where not success" 2>/dev/null || echo 1)"
[ "${APPLIED:-0}" -ge 20 ] && ok "${APPLIED} migrations applied" || bad "expected at least 20 migrations, saw ${APPLIED:-0}"
[ "${FAILED:-1}" -eq 0 ] && ok "no failed migration" || bad "${FAILED} migrations failed"

step "3  Write an append-only audit chain"
# Written into the real audit_events table, with its real foreign keys and its real append-only triggers, rather
# than into a scratch table. A recovery check against a table that does not carry the production constraints would
# not exercise the thing being recovered.
psql_source -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
insert into tenants (id, slug, display_name, tenant_status, data_residency, created_at)
values ('22222222-2222-2222-2222-222222222222', 'recovery-check', 'Recovery Check', 'ACTIVE', 'eu-west', now())
on conflict (id) do nothing;

insert into organizations (id, slug, name, tenant_id, created_at)
values ('11111111-1111-1111-1111-111111111111', 'recovery-check', 'Recovery Check',
        '22222222-2222-2222-2222-222222222222', now())
on conflict (id) do nothing;

do $$
declare
  previous text := repeat('0', 64);
  current_hash text;
  seq integer;
begin
  for seq in 1..25 loop
    -- sha256() is built in; digest() would need the pgcrypto extension, which a restored database is not
    -- guaranteed to have and which the application does not require.
    current_hash := encode(sha256(convert_to(previous || seq::text || '{"event": "recovery-check"}', 'UTF8')), 'hex');
    insert into audit_events (id, organization_id, tenant_id, project_id, actor_subject, action, entity_type,
                              entity_id, payload, sequence, previous_hash, event_hash, occurred_at)
    values (gen_random_uuid(), '11111111-1111-1111-1111-111111111111',
            '22222222-2222-2222-2222-222222222222', null, 'recovery-check', 'RECOVERY_CHECK', 'recovery',
            seq::text, '{"event": "recovery-check"}', seq, previous, current_hash, now());
    previous := current_hash;
  end loop;
end $$;
SQL
SOURCE_COUNT="$(psql_source -c "select count(*) from audit_events where actor_subject = 'recovery-check'")"
SOURCE_HEAD="$(psql_source -c "select event_hash from audit_events where actor_subject = 'recovery-check' order by sequence desc limit 1")"
[ "${SOURCE_COUNT:-0}" -eq 25 ] && ok "25 chained audit events written" || bad "expected 25 audit events, saw ${SOURCE_COUNT:-0}"
[ -n "${SOURCE_HEAD:-}" ] && ok "source head digest ${SOURCE_HEAD:0:16}…" || bad "no head digest"

step "4  Back up"
docker exec "$CONTAINER" pg_dump -U postgres --format=custom --no-owner "$SOURCE_DB" > "$DUMP_FILE"
DUMP_BYTES="$(wc -c < "$DUMP_FILE" | tr -d ' ')"
[ "${DUMP_BYTES:-0}" -gt 10000 ] && ok "dump captured (${DUMP_BYTES} bytes)" || bad "dump implausibly small: ${DUMP_BYTES:-0} bytes"

step "5  Restore into a separate database"
docker exec "$CONTAINER" createdb -U postgres "$RESTORED_DB"
docker exec -i "$CONTAINER" pg_restore -U postgres --no-owner --dbname="$RESTORED_DB" < "$DUMP_FILE" >/dev/null 2>&1
RESTORED_COUNT="$(psql_restored -c "select count(*) from audit_events where actor_subject = 'recovery-check'")"
[ "${RESTORED_COUNT:-0}" -eq "${SOURCE_COUNT:-0}" ] && ok "restored ${RESTORED_COUNT} events" || bad "restored ${RESTORED_COUNT:-0} events, source had ${SOURCE_COUNT:-0}"

step "6  Verify the restored chain reaches the same head"
# Recomputing the chain from the restored rows. Comparing counts alone would pass a restore that silently reordered
# or altered a payload, which is precisely what an append-only ledger exists to detect.
RECOMPUTED="$(psql_restored <<'SQL'
with recursive chain as (
  select sequence,
         encode(sha256(convert_to(repeat('0', 64) || sequence::text || payload::text, 'UTF8')), 'hex') as computed
    from audit_events where actor_subject = 'recovery-check' and sequence = 1
  union all
  select a.sequence,
         encode(sha256(convert_to(c.computed || a.sequence::text || a.payload::text, 'UTF8')), 'hex')
    from audit_events a join chain c on a.sequence = c.sequence + 1
   where a.actor_subject = 'recovery-check'
)
select computed from chain order by sequence desc limit 1;
SQL
)"
MISMATCHES="$(psql_restored -c "select count(*) from audit_events a join audit_events b on b.sequence = a.sequence - 1 and b.actor_subject = a.actor_subject where a.actor_subject = 'recovery-check' and a.previous_hash <> b.event_hash")"
[ "${RECOMPUTED:-x}" = "${SOURCE_HEAD:-y}" ] && ok "restored chain recomputes to the source head digest" || bad "head digest differs after restore: ${RECOMPUTED:-none} vs ${SOURCE_HEAD:-none}"
[ "${MISMATCHES:-1}" -eq 0 ] && ok "every restored link matches its predecessor" || bad "${MISMATCHES} broken links after restore"

step "7  Prove the verification actually detects tampering"
# Without this, a check that always reports success would have passed every step above.
#
# The UPDATE has to disable the append-only trigger first, and that is itself the result worth recording: the
# restored database still refuses an in-place edit of the ledger. Tampering here therefore simulates an attacker
# with enough privilege to drop the trigger, which is the only threat model in which step 6 has anything to catch.
TRIGGER_BLOCKED=0
psql_restored -v ON_ERROR_STOP=1 -c "update audit_events set payload = '{\"event\": \"tampered\"}' where actor_subject = 'recovery-check' and sequence = 12" >/dev/null 2>&1 || TRIGGER_BLOCKED=1
[ "$TRIGGER_BLOCKED" -eq 1 ] && ok "the restored database still enforces the append-only trigger" || bad "audit_events accepted an UPDATE after restore — the append-only trigger did not survive"

psql_restored -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
alter table audit_events disable trigger audit_events_no_update;
update audit_events set payload = '{"event": "tampered"}'
 where actor_subject = 'recovery-check' and sequence = 12;
alter table audit_events enable trigger audit_events_no_update;
SQL
TAMPERED="$(psql_restored <<'SQL'
with recursive chain as (
  select sequence,
         encode(sha256(convert_to(repeat('0', 64) || sequence::text || payload::text, 'UTF8')), 'hex') as computed
    from audit_events where actor_subject = 'recovery-check' and sequence = 1
  union all
  select a.sequence,
         encode(sha256(convert_to(c.computed || a.sequence::text || a.payload::text, 'UTF8')), 'hex')
    from audit_events a join chain c on a.sequence = c.sequence + 1
   where a.actor_subject = 'recovery-check'
)
select computed from chain order by sequence desc limit 1;
SQL
)"
[ "${TAMPERED:-x}" != "${SOURCE_HEAD:-y}" ] && ok "a single altered payload changes the head digest" || bad "tampering was NOT detected — the verification in step 6 proves nothing"

step "Result"
if [ "$FAILURES" -eq 0 ]; then
  printf '\033[32mRecovery verification passed.\033[0m Backup and restore preserve the audit chain, and the check that says so detects tampering.\n'
  exit 0
fi
printf '\033[31mRecovery verification failed with %s problem(s).\033[0m\n' "$FAILURES"
exit 1
