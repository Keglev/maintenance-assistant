#!/usr/bin/env bash
# =============================================================================
# build-docs.sh — Documentation build orchestrator
# Usage: .github/scripts/docs/build-docs.sh <project-dir>
#
# Writes the Lua filter, builds the theme assets, then delegates to sibling
# scripts for each doc type. Output tree mirrors the deployed site under
# <project-dir>/target/docs.
#
# The three report trees are NOT generated here. Each is produced by a *test*
# workflow, uploaded as an artifact, and copied into place only on the run that
# workflow triggered — so any single build has at most one of them, while the
# deployed site has all of them because the deploy step preserves what a given
# build did not regenerate (keep_files).
#
# Prerequisites: pandoc, redocly CLI (optional — skipped when absent)
# =============================================================================
set -euo pipefail

PROJECT_DIR="${1:?Usage: build-docs.sh <project-dir>}"
DOCS_DIR="$PROJECT_DIR/docs"
THEME_DIR="$DOCS_DIR/_theme"
OUTPUT_DIR="$PROJECT_DIR/target/docs"
ASSETS_DIR="$OUTPUT_DIR/assets"
LUA_FILTER="$PROJECT_DIR/target/md-to-html-links.lua"

# Resolve sibling script directory at runtime — safe regardless of working directory
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------------------------------------------------------------------------
# Lua filter — owned here to avoid duplication across sibling scripts.
# Converts .md links to .html and wraps mermaid blocks in a div for the browser.
#
# THE RULE: relative links are the filter's business, absolute links are not.
# A source page becomes a page on the site, so a link to a sibling .md has to
# follow it to .html. A link to github.com points at a file in the repository,
# which is not published and never becomes .html — rewriting it produces a URL
# GitHub does not serve. Two such links were live and 404ing before this rule
# existed; see PROJECT-PHASES 2026-08-19.
# ---------------------------------------------------------------------------
write_lua_filter() {
  mkdir -p "$(dirname "$LUA_FILTER")"
  cat > "$LUA_FILTER" << 'LUA'
-- Anything carrying a scheme (http:, https:, mailto:, …) or protocol-relative
-- (//host/…) belongs to whoever it points at, and leaves this filter untouched.
local function is_absolute(target)
  return target:match("^%a[%w+.%-]*:") ~= nil
      or target:match("^//") ~= nil
end

function Link(el)
  if is_absolute(el.target) then
    return el
  end
  el.target = el.target:gsub("%.md#", ".html#")
  el.target = el.target:gsub("%.md$", ".html")
  return el
end

function CodeBlock(el)
  if el.classes:includes('mermaid') then
    local html = '<div class="mermaid">\n' .. el.text .. '\n</div>'
    return pandoc.RawBlock('html', html)
  end
  return el
end
LUA
  echo "✓ Lua filter written"
}

# ---------------------------------------------------------------------------
# Theme assets — concatenate the CSS partials into one stylesheet and copy the
# runtime JS. Templates reference these at /assets/docs.css and /assets/docs.js.
# Concat order is the cascade order: tokens first (defines the variables every
# later partial consumes), mermaid last.
# ---------------------------------------------------------------------------
build_theme_assets() {
  mkdir -p "$ASSETS_DIR"
  cat \
    "$THEME_DIR/css/tokens.css" \
    "$THEME_DIR/css/base.css" \
    "$THEME_DIR/css/layout.css" \
    "$THEME_DIR/css/components.css" \
    "$THEME_DIR/css/landing.css" \
    "$THEME_DIR/css/content.css" \
    "$THEME_DIR/css/mermaid.css" \
    > "$ASSETS_DIR/docs.css"
  cp "$THEME_DIR/js/docs.js" "$ASSETS_DIR/docs.js"
  echo "✓ Theme assets built (docs.css, docs.js)"
}

# Landing pages are static HTML served at the site root.
copy_landing_pages() {
  cp "$THEME_DIR/index.html"    "$OUTPUT_DIR/index.html"
  cp "$THEME_DIR/index-de.html" "$OUTPUT_DIR/index-de.html"
  echo "✓ Landing pages copied"
}

# Diagram SVGs are generated from .mmd sources and are not versioned, so they are
# rendered before the site build and copied next to the pages that embed them.
# The .mmd sources travel with them: pages link to the source as the thing worth
# reading when the rendering is ambiguous, and that link has to resolve.
copy_diagrams() {
  local SRC="$DOCS_DIR/arc42/diagrams"
  local DEST="$OUTPUT_DIR/architecture/diagrams"
  if [ -d "$SRC" ] && compgen -G "$SRC/*.svg" > /dev/null; then
    mkdir -p "$DEST"
    cp "$SRC"/*.svg "$DEST/"
    compgen -G "$SRC/*.mmd" > /dev/null && cp "$SRC"/*.mmd "$DEST/"
    echo "✓ Diagrams copied (svg + mmd sources)"
  else
    echo "ℹ️  No rendered diagrams found — run docs/scripts/generate-diagrams.mjs first"
  fi
}

# The planning documents are plain text, not markdown, so pandoc does not touch
# them. They are copied verbatim because the architecture pages cite them as the
# source of truth and those links have to resolve.
copy_plain_text_docs() {
  if compgen -G "$DOCS_DIR/*.txt" > /dev/null; then
    cp "$DOCS_DIR"/*.txt "$OUTPUT_DIR/"
    echo "✓ Plain-text planning documents copied"
  fi
}

# GITHUB PAGES MUST NOT RUN JEKYLL OVER PANDOC OUTPUT. Without this file Pages
# jekylls the branch: directories beginning with an underscore and files beginning
# with a dot are dropped, and the site loses whatever happens to be named that way.
#
# WRITTEN BY THE BUILD SINCE 2026-08-26 (Part 3, C2), and that is the point rather
# than a tidy-up: the site publish no longer keeps files it did not produce, so a
# .nojekyll that exists only on gh-pages would be deleted on the first run. It has
# to come from the build or it does not come at all.
write_nojekyll() {
  : > "$OUTPUT_DIR/.nojekyll"
  echo "✓ .nojekyll written"
}

# JaCoCo HTML, downloaded by the workflow to target/reports/backend-coverage.
# Absent on docs-only builds, in which case the deploy step preserves the
# published report.
#
# CI NEVER REACHES THIS FUNCTION WITH ANYTHING TO COPY, and that is worth
# knowing before reading it: the site job downloads no artifacts, and the job
# that does download them publishes each tree straight from target/reports/
# without calling this script. So the copy — and the anchor injection below —
# serve a LOCAL full build, whose whole purpose is to look like the deployed
# site. The anchor is injected here for that reason and no other; in CI it is
# injected in docs.yml's reports job, immediately before those publish steps.
copy_report() {
  local SRC="$PROJECT_DIR/target/reports/$1"
  local DEST="$OUTPUT_DIR/$2"
  if [ -d "$SRC" ] && [ "$(ls -A "$SRC")" ]; then
    mkdir -p "$DEST"
    cp -R "$SRC/." "$DEST/"
    if [ -f "$DEST/index.html" ]; then
      bash "$SCRIPTS_DIR/inject-back-to-docs.sh" "$DEST/index.html"
    fi
    echo "✓ $1 copied to $2"
  else
    echo "ℹ️  No $1 found — skipping"
  fi
}

# ---------------------------------------------------------------------------
# The guard for the defect class "build green, published link dead".
#
# lychee cannot see this one: `offline = true` skips external URLs, so a link to
# github.com is never fetched and a rewritten one never fails a check. The build
# stays green while the page 404s for a reader — which is how two of them
# survived on the published site.
#
# It looks for the exact signature rather than for broken links in general: an
# absolute github.com URL ending in .html in the BUILT output. This repository
# publishes no .html to GitHub, so every match is the filter having rewritten a
# link that was .md in the source. Cheap (one grep over 41 files), needs no
# network, and runs in both workflows because both build through this script.
#
# If a genuine link to a .html file on GitHub is ever needed, this will refuse
# it — and that is the moment to add the exception deliberately, here.
# ---------------------------------------------------------------------------
verify_absolute_links_survived() {
  local offenders
  offenders="$(grep -rhoE 'https?://[^"'"'"' ]*github\.com/[^"'"'"' ]+\.html' "$OUTPUT_DIR" \
    | sort -u || true)"

  if [ -n "$offenders" ]; then
    echo ""
    echo "✗ absolute GitHub links were rewritten to .html — the Lua filter must leave" >&2
    echo "  absolute URLs alone. GitHub does not serve these paths:" >&2
    printf '    %s\n' $offenders >&2
    return 1
  fi
  echo "✓ absolute links intact — no github.com URL was rewritten to .html"
}

echo "==> [build-docs] Starting (PROJECT_DIR=$PROJECT_DIR)"
mkdir -p "$OUTPUT_DIR"

write_lua_filter
build_theme_assets
copy_landing_pages
copy_diagrams
copy_plain_text_docs
bash "$SCRIPTS_DIR/build-markdown-docs.sh" "$PROJECT_DIR"
bash "$SCRIPTS_DIR/build-openapi-docs.sh"  "$PROJECT_DIR"
copy_report backend-coverage  backend/coverage
copy_report frontend-coverage frontend/coverage
copy_report frontend-api-docs frontend/api-docs
write_nojekyll
verify_absolute_links_survived

echo ""
echo "✓ Docs build complete — $(find "$OUTPUT_DIR" -type f | wc -l) files, $(du -sh "$OUTPUT_DIR" | cut -f1)"
