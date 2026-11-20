#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$project_dir"

if ! command -v python3 >/dev/null; then
    printf '%s\n' 'Python 3 is required to print the test results.' >&2
    exit 1
fi

results_dir="$(mktemp -d "${TMPDIR:-/tmp}/aiclient-matrix.XXXXXX")"
trap 'rm -rf -- "$results_dir"' EXIT

gradle_status=0
./gradlew --continue :infrastructure:providerMatrixIntegrationTest "$@" \
    "-PaiClientMatrixResults=$results_dir" || gradle_status=$?

report_status=0
python3 scripts/report-provider-matrix.py "$results_dir" || report_status=$?
if (( gradle_status != 0 )); then
    exit "$gradle_status"
fi
exit "$report_status"
