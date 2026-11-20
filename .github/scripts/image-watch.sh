#!/usr/bin/env bash
# =============================================================================
# image-watch.sh - Reports whether the third-party images of the production
# stack are behind the newest release of their line.
# Usage: .github/scripts/image-watch.sh [compose-file]
#        default compose file: ops/docker-compose.prod.yml
#
# Called monthly by image-watch.yml. Dependabot does not watch ops/: the same
# tags are pinned in the integration tests and the e2e stack, and a pull
# request that moves only the compose file would split them. This script is
# the alarm instead; the bump stays a deliberate change to every copy.
#
# A LINE, PER IMAGE, is what the project chose and what upstream still patches:
#   caddy     X.Y-alpine  behind when a newer X.Z-alpine exists (no backports)
#   keycloak  X.Y         behind when a newer X.Z exists (only the newest minor
#                         gets fixes)
#   pgvector  A.B.C-pgN   behind when a newer release for the same PostgreSQL
#                         major N exists (the PostgreSQL major is a decision)
# A newer major is printed as a notice, never a failure.
#
# Produces: one line per image (pinned tag, its last rebuild, newest in line)
# and the list of tracked files whose copy of a tag differs from the compose
# file.
#
# FAILURE MODE: exits 1 when an image is behind its line, when a tracked file
# pins another tag than the compose file, or when a registry cannot be read
# ("could not read": nothing was checked for that image). Exits 0 otherwise.
# =============================================================================
set -euo pipefail

compose="${1:-ops/docker-compose.prod.yml}"
hub="https://hub.docker.com/v2/repositories"
quay="https://quay.io/api/v1/repository"
status=0

fetch() {
  local out code
  out=$(mktemp)
  code=$(curl -sS -o "$out" -w '%{http_code}' --max-time 30 "$1") || true
  if [ "$code" != "200" ]; then
    echo "::error::could not read $1 (HTTP $code); nothing was checked for this image" >&2
    rm -f "$out"
    return 1
  fi
  cat "$out"
  rm -f "$out"
}

pinned() {
  grep -oE "image: *$1:[A-Za-z0-9._-]+" "$compose" | head -1 | sed -E 's/.*://'
}

# Every tracked copy of the tag must equal the compose file's.
copies() {
  local repo="$1" tag="$2" others
  others=$(git grep -hoE "${repo//./\\.}:[A-Za-z0-9._-]*[A-Za-z0-9]" -- . | sed "s#^${repo}:##" | sort -u | grep -vxF "$tag" || true)
  if [ -n "$others" ]; then
    echo "::error::$repo is pinned as $tag in $compose but also as: $(echo "$others" | tr '\n' ' ')"
    git grep -lE "${repo//./\\.}:" -- . | sed 's/^/    /'
    status=1
  fi
}

report() {
  local name="$1" tag="$2" built="$3" newest="$4" newer_major="$5"
  printf '%-10s pinned %-14s rebuilt %-10s newest in line %s\n' "$name" "$tag" "$built" "$newest"
  [ -n "$newer_major" ] && echo "::notice::$name: a newer major exists ($newer_major); moving to it is a decision, not a bump"
  if [ "$newest" != "$tag" ]; then
    echo "::error::$name is behind its line: pinned $tag, newest $newest"
    status=1
  fi
}

check_caddy() {
  local tag major json tags built newest higher
  tag=$(pinned caddy); major="${tag%%.*}"
  json=$(fetch "$hub/library/caddy/tags?page_size=100&name=-alpine&ordering=last_updated") || { status=1; return; }
  tags=$(jq -r '.results[].name' <<< "$json" | grep -E '^[0-9]+\.[0-9]+-alpine$' | sort -V)
  newest=$(grep -E "^${major}\." <<< "$tags" | tail -1)
  higher=$(awk -F. -v m="$major" '$1 > m' <<< "$tags" | tail -1)
  built=$(fetch "$hub/library/caddy/tags/$tag" | jq -r '.last_updated[0:10]') || built="?"
  report caddy "$tag" "$built" "${newest:-?}" "$higher"
  copies caddy "$tag"
}

check_keycloak() {
  local tag major json tags built newest higher
  tag=$(pinned quay.io/keycloak/keycloak); major="${tag%%.*}"
  json=$(fetch "$quay/keycloak/keycloak/tag/?onlyActiveTags=true&limit=100&filter_tag_name=like:${major}.") || { status=1; return; }
  tags=$(jq -r '.tags[].name' <<< "$json" | grep -E '^[0-9]+\.[0-9]+$' | sort -V)
  newest=$(tail -1 <<< "$tags")
  higher=$(fetch "$quay/keycloak/keycloak/tag/?onlyActiveTags=true&limit=100&filter_tag_name=like:$((major + 1))." \
    | jq -r '.tags[].name' | grep -E '^[0-9]+\.[0-9]+$' | sort -V | tail -1) || higher=""
  built=$(fetch "$quay/keycloak/keycloak/tag/?onlyActiveTags=true&specificTag=$tag" | jq -r '.tags[0].last_modified' \
    | xargs -I{} date -u -d {} +%F) || built="?"
  report keycloak "$tag" "$built" "${newest:-?}" "$higher"
  copies quay.io/keycloak/keycloak "$tag"
}

check_pgvector() {
  local tag pg json newest built
  tag=$(pinned pgvector/pgvector); pg="${tag##*-}"
  json=$(fetch "$hub/pgvector/pgvector/tags?page_size=100&name=-${pg}&ordering=last_updated") || { status=1; return; }
  newest=$(jq -r '.results[].name' <<< "$json" | grep -E "^[0-9]+\.[0-9]+\.[0-9]+-${pg}$" | sort -V | tail -1)
  built=$(fetch "$hub/pgvector/pgvector/tags/$tag" | jq -r '.last_updated[0:10]') || built="?"
  report pgvector "$tag" "$built" "${newest:-?}" ""
  copies pgvector/pgvector "$tag"
}

check_caddy
check_keycloak
check_pgvector
exit "$status"
