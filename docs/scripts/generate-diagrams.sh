#!/usr/bin/env bash
# Thin POSIX wrapper around generate-diagrams.mjs, for CI steps and shell habits.
#
#   ./docs/scripts/generate-diagrams.sh          # render all
#   ./docs/scripts/generate-diagrams.sh --check  # fail if any SVG is missing or stale
set -euo pipefail
exec node "$(dirname "$0")/generate-diagrams.mjs" "$@"
