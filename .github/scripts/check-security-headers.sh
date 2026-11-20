#!/usr/bin/env bash
# =============================================================================
# check-security-headers.sh — Confirms the public site sends its security headers
# Usage: .github/scripts/check-security-headers.sh <url>
#
# Called by deploy-tier.yml after each frontend rollout. The headers are set by Caddy
# at the edge (ops/caddy/Caddyfile), not by the frontend container's nginx,
# so they are checked where a visitor receives them: the public URL, through
# DNS, TLS and the proxy. Each header below must be present; its value is
# Caddy's business and is not compared here.
#
# FAILURE MODE: exits non-zero, naming every missing header, when one or more
# is absent or the URL does not answer; the received headers are printed.
# =============================================================================
set -euo pipefail

url="${1:?Usage: check-security-headers.sh <url>}"
required=(content-security-policy x-frame-options x-content-type-options
  referrer-policy strict-transport-security permissions-policy)

headers=$(curl -fsS --max-time 10 --retry 3 --retry-delay 5 --retry-all-errors -D - -o /dev/null "$url") || {
  echo "::error::$url did not answer"; exit 1; }
received=$(tr -d '\r' <<< "$headers" | tr '[:upper:]' '[:lower:]')

missing=()
for name in "${required[@]}"; do
  grep -q "^$name:" <<< "$received" || missing+=("$name")
done

if [ "${#missing[@]}" -gt 0 ]; then
  echo "$received"
  echo "::error::$url is missing security headers: ${missing[*]}"
  exit 1
fi
echo "all ${#required[@]} security headers present at $url"
