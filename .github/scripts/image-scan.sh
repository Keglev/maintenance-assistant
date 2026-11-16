#!/usr/bin/env bash
# =============================================================================
# image-scan.sh — Writes an image's SBOM and fails on fixable serious CVEs
# Usage: .github/scripts/image-scan.sh <image-ref> <sbom-output.json>
#
# Called by backend-ci and frontend-ci on every pull request (the image is built
# but never pushed) and by both deploy workflows before they push. Produces:
#   - <sbom-output.json>: a CycloneDX SBOM of the image (supplier compliance,
#     BSI TR-03183-2 / EU CRA practice), uploaded by the calling job;
#   - a table of every CRITICAL and HIGH finding in the log;
#   - the gate: exit non-zero when a CRITICAL or HIGH finding HAS A FIX and is
#     not accepted in .trivyignore (each entry there carries its reason and a
#     review date). Findings without a fix are reported, never gating: nothing
#     in this repository could act on them.
#
# Trivy is installed from a pinned release whose SHA-256 is written below,
# never through the trivy-action or setup-trivy, whose tags were hijacked on
# 2026-03-19 to steal CI secrets (CVE-2026-33634). A replaced release asset
# fails the checksum and the job. Callers run this before any registry login,
# so no registry credential exists in the job while the scanner runs.
#
# FAILURE MODE: exits non-zero on a checksum mismatch, when Trivy cannot read
# the image or its vulnerability database, or when the gate finds a fixable
# CRITICAL or HIGH finding; the table above the error names each one.
# =============================================================================
set -euo pipefail

image="${1:?Usage: image-scan.sh <image-ref> <sbom-output.json>}"
sbom="${2:?Usage: image-scan.sh <image-ref> <sbom-output.json>}"

# PIN - bump deliberately: new version, new SHA-256 from that release's
# trivy_<version>_checksums.txt, checked against the downloaded file.
TRIVY_VERSION=0.76.2
TRIVY_SHA256=bc0fc726e017b8927a62b2d3531d7a84ee01769daf5c99ed866dced54089fdbf

bin_dir="${RUNNER_TEMP:-/tmp}/trivy-$TRIVY_VERSION"
if [ ! -x "$bin_dir/trivy" ]; then
  mkdir -p "$bin_dir"
  archive="$bin_dir/trivy.tar.gz"
  curl -fsSL --retry 2 -o "$archive" \
    "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-64bit.tar.gz"
  echo "$TRIVY_SHA256  $archive" | sha256sum -c -
  tar -xzf "$archive" -C "$bin_dir" trivy
fi
trivy="$bin_dir/trivy"

"$trivy" image --quiet --format cyclonedx --output "$sbom" "$image"
echo "SBOM written: $sbom"

# Report first, so a red gate is read next to every serious finding, fixable or not.
"$trivy" image --quiet --skip-db-update --severity CRITICAL,HIGH --format table "$image"

"$trivy" image --quiet --skip-db-update --severity CRITICAL,HIGH --ignore-unfixed \
  --ignorefile .trivyignore --exit-code 1 --format table "$image" > /dev/null || {
  echo "::error::$image has CRITICAL or HIGH findings with a fix available; see the table above"
  exit 1
}
echo "gate passed: no fixable CRITICAL or HIGH finding in $image"
