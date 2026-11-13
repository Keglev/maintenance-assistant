#!/usr/bin/env bash
# =============================================================================
# e2e-relevant.sh — Decides whether a change can affect the rendered test suites
# Usage: .github/scripts/e2e-relevant.sh <base-sha> <head-sha>
#
# Diffs the two commits (merge-base aware) and writes run=true or run=false to
# GITHUB_OUTPUT. The CI workflows call this in a gate job; the e2e and visual
# jobs run only when it answers true, and the aggregator treats a skipped suite
# as a pass.
#
# THE LIST BELOW IS A DENYLIST OF PATHS THE SUITES NEVER READ, not an allowlist
# of paths they do: a path this file has never heard of defaults to RUN. That is
# the safe direction — a new directory skips the suites only after somebody adds
# it here deliberately, with the diff in front of them.
#
# FAILURE MODE: exits non-zero when the diff cannot be computed (unknown sha,
# shallow history) — the gate job then fails and the aggregator fails with it,
# so a broken gate can never skip the suites silently. A base of all zeros
# (first push to a new ref) has no diff to read and answers run=true.
# =============================================================================
set -euo pipefail

base="${1:?Usage: e2e-relevant.sh <base-sha> <head-sha>}"
head="${2:?Usage: e2e-relevant.sh <base-sha> <head-sha>}"

# First push to a new ref: there is no "before" to diff against, so run.
if grep -qE '^0+$' <<<"$base"; then
  echo "base is all zeros (new ref): run=true"
  echo "run=true" >> "$GITHUB_OUTPUT"
  exit 0
fi

merge_base=$(git merge-base "$base" "$head")
changed=$(git diff --name-only "$merge_base" "$head")

run=false
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    # Prose and metadata: nothing the suites load.
    docs/*) ;;
    *.md) ;;
    LICENSE|.gitignore|.gitattributes|.git-blame-ignore-revs|lychee.toml) ;;
    .github/dependabot.yml) ;;
    # Workflows that neither build nor serve what the suites exercise. The two
    # e2e workflow files, the e2e-stack action and this script are deliberately
    # NOT here: changing how a suite runs is a reason to run it.
    .github/workflows/docs.yml|.github/workflows/docs-pr-check.yml) ;;
    .github/workflows/catalogue-watch.yml|.github/workflows/release.yml) ;;
    .github/workflows/backend-deploy.yml|.github/workflows/frontend-deploy.yml) ;;
    *)
      echo "relevant: $f"
      run=true
      ;;
  esac
done <<<"$changed"

echo "run=$run"
echo "run=$run" >> "$GITHUB_OUTPUT"
