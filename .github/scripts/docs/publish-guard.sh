#!/usr/bin/env bash
# =============================================================================
# publish-guard.sh - Decides whether a docs run publishes its area at all
# Usage: .github/scripts/docs/publish-guard.sh <marker> <sha> <output-dir>
#   <marker>      the area's marker path on gh-pages: published-commit.txt for
#                 the site, backend/published-commit.txt, frontend/...
#   <sha>         the commit this run would publish
#   <output-dir>  the directory the publish step pushes for this area
#
# Produces: publish=true|false in $GITHUB_OUTPUT. When true, <output-dir> gets
# a published-commit.txt naming <sha>, so the next run can compare.
#
# WHY: the gh-pages concurrency group serialises the docs runs, but GitHub does
# not guarantee the order in which queued runs start. A run for an older commit
# that starts after a newer one would put the older pages back. The rule is the
# deploys' (deploy-commit-check.sh): skip when the published commit already
# contains this one. A run whose output equals the published area would commit
# nothing but a new marker, so it skips too and the old marker stays. That
# cannot hide a newer publish: the content on gh-pages is already this output.
#
# Outcomes: the published commit contains <sha> -> skip, green, ::notice::;
# output identical to the published area apart from the marker -> skip, green,
# ::notice::; no marker yet (first publish, or gh-pages missing), or anything
# else -> publish. Needs the full history of main in the checkout
# (fetch-depth: 0) for the ancestry test.
# =============================================================================
set -euo pipefail

usage="Usage: publish-guard.sh <marker> <sha> <output-dir>"
marker="${1:?$usage}"
sha="${2:?$usage}"
out="${3:?$usage}"
area=$(dirname "$marker")
[ "$area" = "." ] && area=""

published=""
# Refreshed here, inside the serialised group, so the marker is the current one.
if git fetch -q origin +gh-pages:refs/remotes/origin/gh-pages 2>/dev/null; then
  published=$(git show "origin/gh-pages:$marker" 2>/dev/null | tr -d '[:space:]') || published=""
fi

# Hashes <output-dir> as git would store it, with the published marker in place,
# so equal trees mean the publish would change nothing. A throwaway index keeps
# the checkout's own index untouched.
unchanged() {
  local idx tree
  idx=$(mktemp -u)
  printf '%s\n' "$published" > "$out/published-commit.txt"
  tree=$(GIT_INDEX_FILE="$idx" git --work-tree="$out" add -A -f . &&
    GIT_INDEX_FILE="$idx" git write-tree)
  rm -f "$idx"
  [ "$tree" = "$(git rev-parse "origin/gh-pages:$area")" ]
}

publish=true
if [ -z "$published" ]; then
  echo "no published commit recorded at $marker: publishing $sha"
elif [ "$published" != "$sha" ] && git merge-base --is-ancestor "$sha" "$published" 2>/dev/null; then
  publish=false
  echo "::notice::$published is published and already contains $sha: publish skipped"
elif mkdir -p "$out" && unchanged; then
  publish=false
  echo "::notice::the output for $sha equals the published $marker area: publish skipped"
else
  echo "publishing $sha over $published"
fi

if [ "$publish" = true ]; then
  mkdir -p "$out"
  echo "$sha" > "$out/published-commit.txt"
fi
echo "publish=$publish" >> "${GITHUB_OUTPUT:-/dev/null}"
