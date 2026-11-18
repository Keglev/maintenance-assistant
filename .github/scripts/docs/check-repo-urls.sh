#!/usr/bin/env bash
# =============================================================================
# check-repo-urls.sh - Fails when a docs link to this repository names a missing path
# Usage: .github/scripts/docs/check-repo-urls.sh [<repo-root>]
#
# Docs pages link repository files by absolute GitHub URL, because a relative
# link would leave the published site root, and lychee runs offline, so it
# never checks those URLs. A moved or deleted file therefore breaks them
# silently after the merge. This reads every tracked .md and .html file, takes
# each github.com/Keglev/maintenance-assistant/(blob|tree)/main/<path> URL and
# checks that <path> exists in the checkout. Links pinned to a commit
# (blob/<sha>/...) name a tree that never changes and are not checked.
#
# Run by docs-pr-check in its aggregator job, which runs on every pull request:
# a move outside docs/ breaks docs links too, so the check cannot wait for the
# docs family gate.
#
# Produces: one ::error:: line per broken URL, then a summary line.
#
# FAILURE MODE: exits 1 when any URL names a missing path, and also when no URL
# is found at all, because the docs do carry such links and finding none means
# the pattern or the file list stopped matching, not that the links are fine.
# =============================================================================
set -euo pipefail

cd "${1:-.}"
pattern='github\.com/[Kk]eglev/maintenance-assistant/(blob|tree)/main/[^]()<>"'"'"'`#? ]+'
checked=0
missing=0

while IFS= read -r hit; do
  file=${hit%%:*}
  url=${hit#*:}
  path=${url#*/main/}
  path=${path%/}
  checked=$((checked + 1))
  if [ ! -e "$path" ]; then
    echo "::error file=$file::$url names $path, which is not in the checkout"
    missing=$((missing + 1))
  fi
done < <(git ls-files -z -- '*.md' '*.html' | xargs -0 grep -oE "$pattern" /dev/null || true)

if [ "$checked" -eq 0 ]; then
  echo "::error::no repository URL found in any .md or .html file; the check matched nothing"
  exit 1
fi
echo "repository URLs checked: $checked, missing: $missing"
[ "$missing" -eq 0 ]
