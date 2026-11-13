#!/usr/bin/env bash
# =============================================================================
# claimant-gate.sh — Decides whether a pull request touches a claimant's family
# Usage: .github/scripts/claimant-gate.sh <family> <base-sha> <head-sha>
#        family: backend | frontend | docs
#
# Called by claimant-gate.yml, which every claimant workflow runs as its first
# job on every pull request. Writes run=true or run=false to GITHUB_OUTPUT; the
# claimant's jobs run only on true, and the aggregator passes a family the
# change never touched. This replaces the hand-paired pull_request path lists
# the three claimants used to carry: those had to partition every path between
# three files by hand, and a path in no list made its PRs unmergeable.
#
# THE PARTITION, in one place. docs and frontend are enumerated; BACKEND IS THE
# CATCH-ALL, so a path in no list lands in the backend family and gets gated
# rather than stranded. No path belongs to two families.
#
# On a push event the answer is always run=true: the push trigger's path
# filters have already decided, and this gate only refines pull requests.
#
# FAILURE MODE: exits non-zero on an unknown family or when the diff cannot be
# computed — the gate job fails and the aggregator fails with it, so a broken
# gate can never pass a family silently.
# =============================================================================
set -euo pipefail

family="${1:?Usage: claimant-gate.sh <family> <base-sha> <head-sha>}"
base="${2:?Usage: claimant-gate.sh <family> <base-sha> <head-sha>}"
head="${3:?Usage: claimant-gate.sh <family> <base-sha> <head-sha>}"

answer() {
  echo "run=$1"
  echo "run=$1" >> "$GITHUB_OUTPUT"
  exit 0
}

if [ "${GITHUB_EVENT_NAME:-}" != "pull_request" ]; then
  echo "event is ${GITHUB_EVENT_NAME:-unknown}, not a pull request: the trigger's path filters already decided"
  answer true
fi

is_docs() {
  case "$1" in
    docs/*) return 0 ;;
    .github/workflows/docs.yml|.github/workflows/docs-pr-check.yml) return 0 ;;
    .github/scripts/docs/*) return 0 ;;
  esac
  return 1
}

is_frontend() {
  case "$1" in
    frontend/*|.github/workflows/frontend-ci.yml) return 0 ;;
  esac
  return 1
}

merge_base=$(git merge-base "$base" "$head")
changed=$(git diff --name-only "$merge_base" "$head")

run=false
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$family" in
    docs)     if is_docs "$f"; then echo "docs: $f"; run=true; fi ;;
    frontend) if is_frontend "$f"; then echo "frontend: $f"; run=true; fi ;;
    backend)  if ! is_docs "$f" && ! is_frontend "$f"; then echo "backend (catch-all): $f"; run=true; fi ;;
    *) echo "::error::unknown family '$family'"; exit 1 ;;
  esac
done <<<"$changed"

answer "$run"
