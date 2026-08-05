#!/usr/bin/env bash
# =============================================================================
# build-markdown-docs.sh — Converts the documentation markdown to HTML
# Usage: .github/scripts/docs/build-markdown-docs.sh <project-dir>
#
# Expects the Lua filter at <project-dir>/target/md-to-html-links.lua, written by
# build-docs.sh before this script is called.
#
# Source tree            ->  Output tree (and deployed URL)
#   docs/*.md            ->  target/docs/*.html            /maintenance-assistant/
#   docs/arc42/*.md      ->  target/docs/architecture/     /maintenance-assistant/architecture/
#   docs/adr/*.md        ->  target/docs/adr/              /maintenance-assistant/adr/
#
# Each tree's README.md becomes that directory's index.html, so the sidebar and
# the landing cards can address a directory rather than a filename.
#
# Prerequisites: pandoc
# =============================================================================
set -euo pipefail

PROJECT_DIR="${1:?Usage: build-markdown-docs.sh <project-dir>}"

DOCS_DIR="$PROJECT_DIR/docs"
OUTPUT_DIR="$PROJECT_DIR/target/docs"
TEMPLATE="$DOCS_DIR/_theme/app-docs.html"
LUA_FILTER="$PROJECT_DIR/target/md-to-html-links.lua"

# Pandoc resolves the $nav-docs()$ partial from <data-dir>/templates/. Setting
# this explicitly makes partial resolution work the same on old and new pandoc
# (older versions ignore the template's own directory for partials).
DATA_DIR="$DOCS_DIR/_theme"

# Converts every .md in a source directory, preserving subdirectory structure.
#   $1 source directory   $2 destination directory (relative to OUTPUT_DIR)
#   $3 title prefix shown in the browser tab
convert_tree() {
  local SRC_DIR="$1"
  local DST_DIR="$OUTPUT_DIR/$2"
  local LABEL="$3"
  local MAXDEPTH="${4:-}"

  if [ ! -d "$SRC_DIR" ]; then
    echo "ℹ️  No markdown at $SRC_DIR — skipping"
    return 0
  fi

  local find_args=("$SRC_DIR")
  [ -n "$MAXDEPTH" ] && find_args+=(-maxdepth "$MAXDEPTH")
  find_args+=(-type f -name "*.md")

  local count
  count=$(find "${find_args[@]}" | wc -l)
  if [ "$count" -eq 0 ]; then
    echo "ℹ️  No .md files in $SRC_DIR — skipping"
    return 0
  fi

  echo "==> [build-markdown-docs] Converting $count file(s) from ${SRC_DIR#"$PROJECT_DIR"/}"
  mkdir -p "$DST_DIR"

  find "${find_args[@]}" | while read -r md; do
    rel="${md#"$SRC_DIR"/}"
    base="${rel%.md}"

    # README is the directory's index, so /architecture/ and /adr/ resolve
    # without a filename.
    [ "$base" = "README" ] && base="index"

    out="$DST_DIR/$base.html"
    mkdir -p "$(dirname "$out")"

    # German pages (-de suffix) set lang:de so the template renders <html lang="de">.
    lang="en"
    case "$base" in
      *-de) lang="de" ;;
    esac

    pandoc "$md" \
      --from markdown --to html \
      --data-dir="$DATA_DIR" \
      --template "$TEMPLATE" \
      --lua-filter "$LUA_FILTER" \
      --metadata=title:"$LABEL · $base" \
      --metadata=lang:"$lang" \
      --toc --toc-depth=3 --standalone \
      -o "$out"
    echo "  ✓ $rel -> ${out#"$OUTPUT_DIR"/} ($lang)"
  done
}

# Root pages only (-maxdepth 1): arc42/ and adr/ are converted by their own
# passes below, and _theme/ and scripts/ are not documentation.
convert_tree "$DOCS_DIR"       "."             "Docs"         1
convert_tree "$DOCS_DIR/arc42" "architecture"  "Architecture"
convert_tree "$DOCS_DIR/adr"   "adr"           "Decisions"

echo "✓ Markdown docs complete"
