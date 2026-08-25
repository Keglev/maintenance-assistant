#!/usr/bin/env bash
# =============================================================================
# backup.sh — nightly backup of the maintenance-assistant stack.
#
# Takes two artefacts, because the data is in two places:
#   1. a pg_dump of the application database (custom format, compressed)
#   2. a tar.gz of the protocol-files volume
#
# Both are needed. The database stores only the *path* of an uploaded protocol
# document (domain-model.md: no BLOBs in Postgres); the file itself lives on the
# volume. A database dump on its own restores rows whose source_file points at
# nothing.
#
# Installed by infra/install-backup.sh as /usr/local/bin/maintenance-backup and
# run by the maintenance-backup.timer systemd unit. Safe to run by hand:
#
#   ssh deploy@<host> maintenance-backup
#
# Runs as the unprivileged `deploy` user: it needs Docker (to reach the database
# container and the volume) and read access to .env.prod, both of which that
# user already has. It never needs root.
#
# Every step verifies its own output before the artefact is given its final
# name, so a file in the backup directory is one that was checked, not merely
# one that was written. A failure exits non-zero, which is what makes
# `systemctl status maintenance-backup` meaningful.
# =============================================================================
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/maintenance-assistant}"
BACKUP_DIR="${BACKUP_DIR:-${APP_DIR}/backups}"
ENV_FILE="${ENV_FILE:-${APP_DIR}/.env.prod}"
PG_CONTAINER="${PG_CONTAINER:-maintenance-postgres}"
FILES_VOLUME="${FILES_VOLUME:-maintenance-assistant_protocol-files}"
# 14 nightly runs. Two weeks is long enough that a problem noticed on a Monday
# still has a clean copy behind it, and short enough that the whole set stays a
# rounding error against 75 GB of disk.
KEEP="${KEEP:-14}"

STAMP="$(date +%F)"
DUMP_NAME="maintenance-db-${STAMP}.dump"
FILES_NAME="protocol-files-${STAMP}.tar.gz"

log()  { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() { printf '%s  ERROR: %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || fail "docker not on PATH"
[ -r "$ENV_FILE" ] || fail "cannot read ${ENV_FILE} — is this running as the deploy user?"
docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" 2>/dev/null | grep -qx true \
  || fail "container ${PG_CONTAINER} is not running"
docker volume inspect "$FILES_VOLUME" >/dev/null 2>&1 \
  || fail "volume ${FILES_VOLUME} does not exist"

# set -a exports everything the file defines, which is how POSTGRES_* reach the
# docker calls below without being repeated here.
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
: "${POSTGRES_DB:?not set in ${ENV_FILE}}"
: "${POSTGRES_USER:?not set in ${ENV_FILE}}"
: "${POSTGRES_PASSWORD:?not set in ${ENV_FILE}}"

# 0700: a dump of this database is every protocol in it. It does not get to be
# world-readable just because it is a backup.
mkdir -p "$BACKUP_DIR"
chmod 0700 "$BACKUP_DIR"

# The image the database already runs, so pg_restore and tar come from a layer
# that is guaranteed present — no extra pull, and no second version of the
# Postgres tooling to keep in step with the server.
PG_IMAGE="$(docker inspect -f '{{.Config.Image}}' "$PG_CONTAINER")"

log "backup starting: db=${POSTGRES_DB} volume=${FILES_VOLUME} keep=${KEEP}"

# ---------------------------------------------------------------------------
# 1. Application database
# ---------------------------------------------------------------------------
# Custom format (-Fc) rather than plain SQL piped through gzip. It is already
# compressed, so nothing is lost on that count, and it is the only format
# pg_restore can list, verify and restore selectively — which is what makes the
# check below possible at all. A gzipped .sql can only be verified by restoring
# it somewhere.
#
# PGPASSWORD is passed by name, not by value: `docker exec -e PGPASSWORD` copies
# it from this script's environment, so the secret never appears in the docker
# command line where `ps` would show it.
log "dumping database ${POSTGRES_DB}"
export PGPASSWORD="$POSTGRES_PASSWORD"
if ! docker exec -e PGPASSWORD "$PG_CONTAINER" \
      pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --compress=9 \
      > "${BACKUP_DIR}/${DUMP_NAME}.part"; then
  rm -f "${BACKUP_DIR}/${DUMP_NAME}.part"
  fail "pg_dump failed"
fi

# Verify before promoting. A dump that pg_restore cannot read is not a backup,
# and finding that out on the night it is needed is the whole failure mode this
# guards against. Runs in a throwaway container so the live database is not
# touched; --entrypoint bypasses the image's postgres entrypoint, and --user
# keeps the container from needing to read a file owned by someone else.
log "verifying dump with pg_restore --list"
if ! docker run --rm --user "$(id -u):$(id -g)" --entrypoint pg_restore \
      -v "${BACKUP_DIR}:/backup:ro" "$PG_IMAGE" \
      --list "/backup/${DUMP_NAME}.part" > /dev/null; then
  rm -f "${BACKUP_DIR}/${DUMP_NAME}.part"
  fail "dump did not verify — discarded, no backup written"
fi
mv "${BACKUP_DIR}/${DUMP_NAME}.part" "${BACKUP_DIR}/${DUMP_NAME}"
chmod 0600 "${BACKUP_DIR}/${DUMP_NAME}"
log "database dump ok: ${DUMP_NAME} ($(du -h "${BACKUP_DIR}/${DUMP_NAME}" | cut -f1))"

# ---------------------------------------------------------------------------
# 2. Protocol files volume
# ---------------------------------------------------------------------------
# tar runs as root inside the container because the volume belongs to the
# backend's uid, and writes to stdout; the redirect happens on the host, so the
# archive itself ends up owned by the deploy user rather than by root.
log "archiving volume ${FILES_VOLUME}"
if ! docker run --rm --entrypoint tar \
      -v "${FILES_VOLUME}:/data:ro" "$PG_IMAGE" \
      -czf - -C /data . > "${BACKUP_DIR}/${FILES_NAME}.part"; then
  rm -f "${BACKUP_DIR}/${FILES_NAME}.part"
  fail "volume archive failed"
fi

log "verifying archive"
if ! tar -tzf "${BACKUP_DIR}/${FILES_NAME}.part" > /dev/null; then
  rm -f "${BACKUP_DIR}/${FILES_NAME}.part"
  fail "archive did not verify — discarded, no backup written"
fi
mv "${BACKUP_DIR}/${FILES_NAME}.part" "${BACKUP_DIR}/${FILES_NAME}"
chmod 0600 "${BACKUP_DIR}/${FILES_NAME}"
DOC_COUNT="$(tar -tzf "${BACKUP_DIR}/${FILES_NAME}" | grep -c '\.txt$' || true)"
log "volume archive ok: ${FILES_NAME} ($(du -h "${BACKUP_DIR}/${FILES_NAME}" | cut -f1), ${DOC_COUNT} documents)"

# ---------------------------------------------------------------------------
# 3. Rotation
# ---------------------------------------------------------------------------
# Newest-first, drop everything past KEEP. Deliberately per pattern rather than
# "delete anything older than N days": if the timer stops running, retention
# should hold the last good copies rather than expire them on schedule.
prune() {
  local pattern="$1" removed=0 victim
  while IFS= read -r victim; do
    [ -n "$victim" ] || continue
    rm -f "$victim"
    removed=$((removed + 1))
  done < <(ls -1t "${BACKUP_DIR}"/${pattern} 2>/dev/null | tail -n "+$((KEEP + 1))")
  [ "$removed" -eq 0 ] || log "pruned ${removed} old ${pattern}"
}
prune 'maintenance-db-*.dump'
prune 'protocol-files-*.tar.gz'

# Any *.part left behind is a run that died mid-write; it is not a backup and
# must not be mistaken for one on the next listing.
find "$BACKUP_DIR" -maxdepth 1 -name '*.part' -mmin +120 -delete 2>/dev/null || true

log "backup complete: $(ls -1 "${BACKUP_DIR}"/maintenance-db-*.dump 2>/dev/null | wc -l) dumps, $(ls -1 "${BACKUP_DIR}"/protocol-files-*.tar.gz 2>/dev/null | wc -l) archives, $(du -sh "$BACKUP_DIR" | cut -f1) total"
