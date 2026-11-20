#!/usr/bin/env bash
# =============================================================================
# deploy-commit-check.sh — Compares the commit a deployed tier serves with another
# Usage: .github/scripts/deploy-commit-check.sh stale  <url> <candidate-sha>
#        .github/scripts/deploy-commit-check.sh served <url> <expected-sha>
#
# Called by deploy-tier.yml for either tier. <url> returns JSON with
# a "commit" field: /api/health for the backend, /build-info.json for the
# frontend. Read from the runner over the public internet, so DNS, TLS and the
# reverse proxy are part of what is checked.
#
# stale (before the rollout): queued deploys are not guaranteed to start in
#   order, so an older commit's deploy can arrive after a newer one. When the
#   served commit already contains <candidate-sha> (it is a descendant in the
#   checked-out history), writes stale=true to GITHUB_OUTPUT and a notice; the
#   job then skips the rollout, and nothing is lost because the live build
#   includes the candidate. Every other case writes stale=false: the same
#   commit, an unrelated or unknown one, or no answer (a first deploy, a down
#   tier) - a deploy is never skipped on a guess. Needs the history of both
#   commits in the working directory.
# served (after the rollout): polls until the served commit equals
#   <expected-sha>, because a green health answer alone cannot tell the new
#   build from the old one still running. ATTEMPTS (default 20) and INTERVAL
#   seconds (default 6) tune the polling.
#
# FAILURE MODE: served exits non-zero when the commit never matches within the
# attempts, printing the last body read (or "no answer"), so a run shows
# whether the tier was down or answered with another build. stale never fails
# the job; a usage error exits 2.
# =============================================================================
set -euo pipefail

usage="Usage: deploy-commit-check.sh stale|served <url> <sha>"
mode="${1:?$usage}"
url="${2:?$usage}"
expected="${3:?$usage}"
attempts="${ATTEMPTS:-20}"
interval="${INTERVAL:-6}"

# Prints the commit field of the JSON at the URL, or nothing when there is no answer.
served_commit() {
  local body
  body=$(curl -fsS --max-time 10 "$url" 2>/dev/null) || return 0
  sed -n 's/.*"commit":"\([^"]*\)".*/\1/p' <<< "$body"
}

case "$mode" in
  stale)
    served=$(served_commit)
    verdict=false
    if [ -z "$served" ]; then
      echo "no commit served at $url: deploying $expected"
    elif [ "$served" = "$expected" ]; then
      echo "$expected is already live: deploying it again"
    elif git merge-base --is-ancestor "$expected" "$served" 2>/dev/null; then
      verdict=true
      echo "::notice::$served is live and already contains $expected: rollout skipped"
    else
      echo "live commit $served does not contain $expected: deploying"
    fi
    echo "stale=$verdict" >> "${GITHUB_OUTPUT:-/dev/null}"
    ;;
  served)
    last="no answer"
    for attempt in $(seq 1 "$attempts"); do
      commit=$(served_commit)
      if [ "$commit" = "$expected" ]; then
        echo "serving $expected after $attempt attempt(s)"
        exit 0
      fi
      [ -n "$commit" ] && last="commit $commit"
      echo "attempt $attempt: $last, waiting for $expected"
      sleep "$interval"
    done
    echo "::error::$url never served $expected (last: $last)"
    exit 1
    ;;
  *)
    echo "$usage" >&2
    exit 2
    ;;
esac
