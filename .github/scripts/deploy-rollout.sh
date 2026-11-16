#!/usr/bin/env bash
# =============================================================================
# deploy-rollout.sh — Rolls one compose service out on the production host
# Usage (on the host, streamed by the deploy job):
#   ssh <host> 'bash -s -- <service> <env-key> [<image>]' < deploy-rollout.sh
#   e.g. backend BACKEND_IMAGE ghcr.io/keglev/maintenance-assistant/backend:sha-<commit>
#
# Run by backend-deploy.yml and frontend-deploy.yml. With <image>, first records
# it in .env.prod as <env-key>=<image>, so the file always names the image that
# runs and every later manual `docker compose ... --env-file .env.prod` keeps
# it. The edit is guarded: written to a temporary file, accepted only when the
# key line is the sole difference and appears exactly once, the previous file
# kept as .env.prod.bak, then moved into place in one step. Without <image>
# (a manual redeploy) the file is not touched and the image it names is used.
# Then pulls and recreates the service and prunes dangling images.
#
# Prints "deployed-image=<value>" last, so the job can read which image now
# runs. DEPLOY_DIR overrides /opt/maintenance-assistant (used by tests).
#
# FAILURE MODE: exits non-zero before touching the running service when the
# guarded edit does not produce exactly the expected file (.env.prod is left
# unchanged), and when pull or up fails.
# =============================================================================
set -euo pipefail

service="${1:?Usage: deploy-rollout.sh <service> <env-key> [<image>]}"
key="${2:?Usage: deploy-rollout.sh <service> <env-key> [<image>]}"
image="${3:-}"
dir="${DEPLOY_DIR:-/opt/maintenance-assistant}"
env_file="$dir/.env.prod"
compose="${COMPOSE_CMD:-docker compose} -f $dir/docker-compose.prod.yml --env-file $env_file"

if [ -n "$image" ]; then
  tmp="$env_file.tmp"
  if grep -q "^$key=" "$env_file"; then
    sed "s|^$key=.*|$key=$image|" "$env_file" > "$tmp"
  else
    { cat "$env_file"; printf '%s=%s\n' "$key" "$image"; } > "$tmp"
  fi
  if ! diff -q <(grep -v "^$key=" "$env_file") <(grep -v "^$key=" "$tmp") > /dev/null ||
     [ "$(grep -c "^$key=" "$tmp")" != 1 ] ||
     ! grep -qxF "$key=$image" "$tmp"; then
    rm -f "$tmp"
    echo "refusing to edit $env_file: the result would change more than the $key line" >&2
    exit 1
  fi
  cp -p "$env_file" "$env_file.bak"
  chmod --reference="$env_file" "$tmp"
  mv "$tmp" "$env_file"
  echo "$env_file now names $image"
fi

$compose pull "$service"
$compose up -d "$service"
# Superseded layers accumulate on the disk otherwise. Dangling only: tagged
# images are what a rollback needs.
${DOCKER_CMD:-docker} image prune -f > /dev/null

echo "deployed-image=$(sed -n "s/^$key=//p" "$env_file")"
