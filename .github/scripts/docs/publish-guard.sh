#!/usr/bin/env bash
# =============================================================================
# publish-guard.sh - Stops an older docs run from overwriting a newer publish
# Usage: .github/scripts/docs/publish-guard.sh <marker> <sha> <output-dir>
#   <marker>      the area's marker path on gh-pages: published-commit.txt for
#                 the site, backend/published-commit.txt, frontend/...
#   <sha>         the commit this run would publish
#   <output-dir>  the directory the publish step pushes for this area
#
# Produces: stale=true|false in $GITHUB_OUTPUT. When false, <output-dir>
# gets a published-commit.txt naming <sha>, so the next run can compare.
#
# WHY: the gh-pages concurrency group serialises the docs runs, but GitHub does
# not guarantee the order in which queued runs start. A run for an older commit
# that starts after a newer one would put the older pages back. The rule is the
# deploys' (deploy-commit-check.sh): skip when the published commit already
# contains this one.
#
# Outcomes: no marker yet (first publish, or gh-pages missing) -> publish; the
# same commit -> publish again; the published commit contains <sha> -> skip,
# green, with a ::notice::; anything else -> publish. Needs the full history
# of main in the checkout (fetch-depth: 0) for the ancestry test.
# =============================================================================
set -euo pipefail

usage="Usage: publish-guard.sh <marker> <sha> <output-dir>"
marker="${1:?$usage}"
sha="${2:?$usage}"
out="${3:?$usage}"

published=""
# Refreshed here, inside the serialised group, so the marker is the current one.
if git fetch -q origin +gh-pages:refs/remotes/origin/gh-pages 2>/dev/null; then
  published=$(git show "origin/gh-pages:$marker" 2>/dev/null | tr -d '[:space:]') || published=""
fi

stale=false
if [ -z "$published" ]; then
  echo "no published commit recorded at $marker: publishing $sha"
elif [ "$published" = "$sha" ]; then
  echo "$sha is already published: publishing it again"
elif git merge-base --is-ancestor "$sha" "$published" 2>/dev/null; then
  stale=true
  echo "::notice::$published is published and already contains $sha: publish skipped"
else
  echo "published commit $published does not contain $sha: publishing"
fi

if [ "$stale" = false ]; then
  mkdir -p "$out"
  echo "$sha" > "$out/published-commit.txt"
fi
echo "stale=$stale" >> "${GITHUB_OUTPUT:-/dev/null}"
