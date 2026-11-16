#!/usr/bin/env bash
# =============================================================================
# release-version-check.sh — Refuses a release whose tag disagrees with the code
# Usage: .github/scripts/release-version-check.sh <tag>
#
# Called by release.yml before it creates a GitHub Release from a pushed v* tag.
# The application carries one version: frontend/package.json "version" and the
# project <version> of backend/pom.xml must be equal, and the tag must be that
# version with a leading "v" (v1.4.0 for 1.4.0). /api/health reports the pom
# version, so a tag that disagrees would publish a release whose running build
# names another number.
#
# FAILURE MODE: exits non-zero, printing all three values, when any of them
# differ or a version cannot be read; the release is not created.
# =============================================================================
set -euo pipefail

tag="${1:?Usage: release-version-check.sh <tag>}"

frontend=$(sed -n 's/^  "version": "\([^"]*\)",$/\1/p' frontend/package.json)
# The project version is the <version> right after the project's own artifactId;
# the parent's <version> above it belongs to Spring Boot.
backend=$(sed -n '/^  <artifactId>maintenance-assistant<\/artifactId>$/,/<version>/s/.*<version>\(.*\)<\/version>.*/\1/p' backend/pom.xml)

echo "tag:                   $tag"
echo "frontend/package.json: ${frontend:-<not found>}"
echo "backend/pom.xml:       ${backend:-<not found>}"

if [ -z "$frontend" ] || [ -z "$backend" ]; then
  echo "::error::could not read a version; see the values above"
  exit 1
fi
if [ "$frontend" != "$backend" ]; then
  echo "::error::package.json says $frontend but pom.xml says $backend; bump both in one commit"
  exit 1
fi
if [ "$tag" != "v$frontend" ]; then
  echo "::error::tag $tag does not match the code version $frontend; expected v$frontend"
  exit 1
fi
echo "versions agree: $frontend"
