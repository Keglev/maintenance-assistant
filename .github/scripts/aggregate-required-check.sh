#!/usr/bin/env bash
# =============================================================================
# aggregate-required-check.sh — The verdict behind each claimant's required check
# Usage: CHANGES_RESULT=... CHANGES_RUN=... BUILD_RESULT=... \
#        [OPTIONAL_RESULTS="e2e=success visual=skipped ..."] \
#        .github/scripts/aggregate-required-check.sh
#
# Runs in every claimant's last job — the one whose id is the required check —
# under if: always(), because a SKIPPED job reports success to branch
# protection, which is the one outcome an aggregator exists to prevent.
#
# The verdict, in order: the claimant gate itself must have succeeded (a gate
# that cannot answer must never pass a family silently); a family the change
# does not touch passes; otherwise the family's build must have succeeded and
# every optional suite must have succeeded or been skipped by its own gate.
#
# FAILURE MODE: any unexpected result — failure, cancelled, or a skip where a
# run was due — exits non-zero and the required check goes red.
# =============================================================================
set -euo pipefail

[ "${CHANGES_RESULT:?}" = "success" ] || {
  echo "::error::the claimant gate did not succeed (${CHANGES_RESULT})"; exit 1; }

if [ "${CHANGES_RUN:?}" = "false" ]; then
  echo "this family is untouched by the change: the required check passes"
  exit 0
fi

[ "${BUILD_RESULT:?}" = "success" ] || {
  echo "::error::the build did not succeed (${BUILD_RESULT})"; exit 1; }

for pair in ${OPTIONAL_RESULTS:-}; do
  name="${pair%%=*}"
  result="${pair#*=}"
  case "$result" in
    success|skipped) ;;
    *) echo "::error::${name} did not succeed (${result})"; exit 1 ;;
  esac
done

echo "required check green (build: success; optional: ${OPTIONAL_RESULTS:-none})"
