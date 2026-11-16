#!/usr/bin/env bash
# =============================================================================
# index-live-corpus.sh — Seeds and indexes the demo corpus into an empty database
# Usage: .github/scripts/index-live-corpus.sh <app-jar> <corpus-ndjson>
#
# Called by backend-llm-live-it.yml before the llm-live tests, which measure
# retrieval against the full indexed corpus and need it in place first. Starts
# the packaged application twice against the database named by
# SPRING_DATASOURCE_URL, and the files directory named by MAINTENANCE_FILES_PATH:
#   1. seed:  CORPUS_SEED_ENABLED=true. Inserts every protocol of the corpus
#             file as RECEIVED. No provider call.
#   2. index: INGESTION_BACKLOG_ON_STARTUP=true. Chunks and embeds every
#             RECEIVED protocol through the real provider (needs LLM_API_KEY).
# Two starts, not one: the seed runner and the backlog runner carry no order
# relative to each other, so in a single start the backlog can run first and
# enqueue nothing.
#
# Each phase ends when the database shows it done; the application is then
# stopped. The psql checks read PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE.
# Each phase's application log is written to LIVE_LOG_DIR (default /tmp).
#
# FAILURE MODE: exits non-zero when the application exits early, when a phase
# does not finish within PHASE_TIMEOUT seconds (default 900), or when any
# protocol ends FAILED; the tail of that phase's log is printed. The tests
# never run against a partial index.
# =============================================================================
set -euo pipefail

jar="${1:?Usage: index-live-corpus.sh <app-jar> <corpus-ndjson>}"
corpus="${2:?Usage: index-live-corpus.sh <app-jar> <corpus-ndjson>}"
timeout="${PHASE_TIMEOUT:-900}"
log_dir="${LIVE_LOG_DIR:-/tmp}"
mkdir -p "$log_dir"

expected=$(grep -c . "$corpus")
echo "corpus file holds $expected protocols"

# Prints the query's single value, or nothing while the schema does not exist yet.
sql() {
  psql -tAc "$1" 2>/dev/null || true
}

seed_done() {
  grep -q "Corpus seed finished" "$1" &&
    [ "$(sql "SELECT count(*) FROM protocol")" = "$expected" ]
}

index_done() {
  [ "$(sql "SELECT count(*) FROM protocol WHERE status IN ('INDEXED', 'FAILED')")" = "$expected" ]
}

run_phase() {
  local name="$1" check="$2"
  shift 2
  local log="$log_dir/live-corpus-$name.log"
  echo "phase $name: starting the application"
  env "$@" java -jar "$jar" > "$log" 2>&1 &
  local pid=$!
  local waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "phase $name: the application exited before the phase finished" >&2
      tail -n 60 "$log" >&2
      exit 1
    fi
    if "$check" "$log"; then
      kill "$pid"
      wait "$pid" || true
      echo "phase $name: done after ${waited}s"
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
  done
  echo "phase $name: not finished after ${timeout}s" >&2
  kill "$pid" || true
  tail -n 60 "$log" >&2
  exit 1
}

run_phase seed seed_done CORPUS_SEED_ENABLED=true INGESTION_BACKLOG_ON_STARTUP=false
run_phase index index_done CORPUS_SEED_ENABLED=false INGESTION_BACKLOG_ON_STARTUP=true

failed=$(sql "SELECT count(*) FROM protocol WHERE status = 'FAILED'")
if [ "$failed" != "0" ]; then
  echo "$failed protocol(s) ended FAILED; the index is incomplete" >&2
  tail -n 60 "$log_dir/live-corpus-index.log" >&2
  exit 1
fi
echo "indexed: $expected protocols, $(sql "SELECT count(*) FROM chunk") chunks"
