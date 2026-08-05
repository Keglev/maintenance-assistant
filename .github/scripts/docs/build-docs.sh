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
# ---------------------------------------------------------------------------
write_lua_filter() {
  mkdir -p "$(dirname "$LUA_FILTER")"
  cat > "$LUA_FILTER" << 'LUA'
function Link(el)
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

# JaCoCo HTML, downloaded by the workflow to target/reports/backend-coverage.
# Absent on docs-only builds, in which case the deploy step preserves the
# published report.
copy_report() {
  local SRC="$PROJECT_DIR/target/reports/$1"
  local DEST="$OUTPUT_DIR/$2"
  if [ -d "$SRC" ] && [ "$(ls -A "$SRC")" ]; then
    mkdir -p "$DEST"
    cp -R "$SRC/." "$DEST/"
    echo "✓ $1 copied to $2"
  else
    echo "ℹ️  No $1 found — skipping"
  fi
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

echo ""
echo "✓ Docs build complete — $(find "$OUTPUT_DIR" -type f | wc -l) files, $(du -sh "$OUTPUT_DIR" | cut -f1)"
