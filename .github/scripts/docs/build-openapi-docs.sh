#!/usr/bin/env bash
# =============================================================================
# build-openapi-docs.sh — Renders the OpenAPI spec to HTML with Redocly
# Usage: .github/scripts/docs/build-openapi-docs.sh <project-dir>
#
# Redocly output is fully self-contained (its own CSS/JS), so this script does
# not touch the docs theme.
#
# The spec is NOT committed: it is written by OpenApiSpecIT during `mvn verify`
# and published as the backend-api-docs artifact. Two locations are therefore
# searched, in order:
#
#   target/reports/backend-api-docs/openapi.json  — downloaded artifact (CI)
#   backend/target/openapi/openapi.json           — a local `mvn verify` (laptop)
#
# When neither exists the script skips with a notice rather than failing: a
# docs-only build legitimately has no spec, and the deploy step preserves the
# published API page it did not regenerate.
#
# Prerequisites: redocly CLI (npx @redocly/cli), when a spec is present
# =============================================================================
set -euo pipefail

PROJECT_DIR="${1:?Usage: build-openapi-docs.sh <project-dir>}"

API_OUT="$PROJECT_DIR/target/docs/backend/api-docs"

SPEC=""
for candidate in \
  "$PROJECT_DIR/target/reports/backend-api-docs/openapi.json" \
  "$PROJECT_DIR/backend/target/openapi/openapi.json"
do
  if [ -f "$candidate" ]; then
    SPEC="$candidate"
    break
  fi
done

if [ -z "$SPEC" ]; then
  echo "ℹ️  No OpenAPI spec found — skipping API reference"
  exit 0
fi

echo "==> [build-openapi-docs] Rendering ${SPEC#"$PROJECT_DIR"/}"
mkdir -p "$API_OUT"

npx --yes @redocly/cli build-docs "$SPEC" -o "$API_OUT/index.html"

# The raw document stays downloadable next to the rendered page: a reader may
# want to import it into a client, and the deployed URL is a stable place to
# point at.
cp "$SPEC" "$API_OUT/openapi.json"

# Inject a fixed-position "back to docs" link into the self-contained Redocly
# page, which has no place for the site chrome. Applied at build time because
# this file is regenerated on every docs build.
sed -i 's|<body[^>]*>|&<a id="back-to-docs" href="/maintenance-assistant/" style="position:fixed;top:8px;right:12px;z-index:9999;font:14px sans-serif;padding:6px 12px;background:#0d9488;color:#fff;text-decoration:none;border-radius:4px;box-shadow:0 1px 4px rgba(0,0,0,0.2);">\&larr; Back to docs</a>|' "$API_OUT/index.html"

echo "✓ Redocly HTML generated at backend/api-docs/index.html (back-to-docs link injected)"
