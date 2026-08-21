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

# The self-contained Redocly page has no place for the site chrome, so the way
# back to the documentation site is injected at build time — this file is
# regenerated on every docs build, so nothing is hand-edited.
#
# The markup and the assertion moved into the shared injector when the coverage
# and Compodoc trees needed the same anchor. It reports its own success, and it
# FAILS THE BUILD if the anchor is not there afterwards, which is why the line
# below no longer claims the injection happened.
bash "$(dirname "${BASH_SOURCE[0]}")/inject-back-to-docs.sh" "$API_OUT/index.html"

echo "✓ Redocly HTML generated at backend/api-docs/index.html"
