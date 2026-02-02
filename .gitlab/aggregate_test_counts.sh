#!/usr/bin/env bash

set -e

# Aggregate test counts from all test jobs using jq for all processing
# Usage: aggregate_test_counts.sh [-v] [aggregate_dir]

# https://docs.gitlab.com/ci/variables/predefined_variables/

VERBOSE=0
if [ "$1" = "-v" ]; then
    VERBOSE=1
    shift
fi

AGGREGATE_DIR="${1:-./test_counts_aggregate}"
OUTPUT_FILE="test_counts_summary.json"
REPORT_FILE="test_counts_report.md"

log_verbose() {
    if [ $VERBOSE -eq 1 ]; then
        echo "[aggregate] $*" >&2
    fi
}

mkdir -p "$AGGREGATE_DIR"

echo "Aggregating test counts..."
log_verbose "Pipeline ID: ${CI_PIPELINE_ID:-unknown}"
log_verbose "Commit: ${CI_COMMIT_SHA:-unknown}"
log_verbose "Branch: ${CI_COMMIT_BRANCH:-unknown}"
log_verbose "Aggregate directory: $AGGREGATE_DIR"
log_verbose "Output file: $OUTPUT_FILE"
log_verbose "Report file: $REPORT_FILE"

# Find all test count files (exclude summary to avoid reprocessing previous runs)
log_verbose "Searching for test count files (test_counts_<jobid>.json)"
mapfile -t COUNT_FILES < <(find . -name "test_counts_*.json" -not -name "$OUTPUT_FILE" -type f 2>/dev/null | sort)
JOB_COUNT=${#COUNT_FILES[@]}
log_verbose "Found $JOB_COUNT test count files"

if [ $JOB_COUNT -eq 0 ]; then
    echo "No test count files found"
    exit 0
fi

# Validate and filter out invalid JSON files
log_verbose "Validating JSON files"
VALID_FILES=()
INVALID_COUNT=0
GITLAB_BASE_URL="${CI_PROJECT_URL}"

for file in "${COUNT_FILES[@]}"; do
    # More thorough validation: try to parse the structure we expect
    if jq -e 'type == "object" and has("ci_job_id") and has("total_tests")' "$file" >/dev/null 2>&1; then
        VALID_FILES+=("$file")
    else
        # Extract job ID from filename (e.g., test_counts_1234.json -> 1234)
        filename=$(basename "$file")
        job_id="${filename#test_counts_}"
        job_id="${job_id%.json}"
        artifact_url="${GITLAB_BASE_URL}/-/jobs/${job_id}/artifacts/file/${filename}"

        echo "⚠️  WARNING: Skipping invalid/empty JSON file: $file" >&2
        echo "   Artifact URL: $artifact_url" >&2
        log_verbose "Invalid JSON file: $file ($artifact_url)"
        INVALID_COUNT=$((INVALID_COUNT + 1))
    fi
done

VALID_COUNT=${#VALID_FILES[@]}
log_verbose "Valid files: $VALID_COUNT, Invalid files: $INVALID_COUNT"

if [ $VALID_COUNT -eq 0 ]; then
    echo "ERROR: No valid test count files found (all $JOB_COUNT files are invalid)"
    exit 1
fi

# Use only valid files for processing
COUNT_FILES=("${VALID_FILES[@]}")
JOB_COUNT=$VALID_COUNT

# Process ALL files with a SINGLE jq invocation
log_verbose "Processing all files with jq"

# Capture jq output and errors
JQ_ERROR_FILE=$(mktemp)
trap 'rm -f "$JQ_ERROR_FILE"' EXIT

if ! AGGREGATED_DATA=$(jq -s '
# Sort by base job name (before colon), then jvm_version (numeric), then test_category
. | sort_by([
  (.ci_job_name | split(":")[0]),
  (if .jvm_version == "stable" then 100 elif ((.jvm_version | gsub("[^0-9]"; "")) as $nums | $nums == "") then 0 else (.jvm_version | gsub("[^0-9]"; "") | tonumber) end),
  .test_category
]) |
{
  pipeline_id: $pipeline_id,
  commit_sha: $commit_sha,
  branch: $branch,
  timestamp: $timestamp,
  test_jobs: .,
  summary: {
    total_tests: (map(.total_tests) | add),
    total_passed: (map(.passed_tests) | add),
    total_failed: (map(.failed_tests) | add),
    total_skipped: (map(.skipped_tests) | add)
  },
  table_rows: map(
    [.test_category, .jvm_version, .ci_job_name, .total_tests, .passed_tests, .failed_tests, .skipped_tests] |
    "| \(.[0]) | \(.[1]) | \(.[2]) | \(.[3]) | \(.[4]) | \(.[5]) | \(.[6]) |"
  ),
  zero_test_jobs: map(
    select(.total_tests == 0) |
    {
      category: .test_category,
      jvm: .jvm_version,
      job: .ci_job_name,
      alert: "⚠️ **WARNING**: Zero tests in \(.test_category) on JVM \(.jvm_version) (job: \(.ci_job_name))"
    }
  )
}
' \
  --arg pipeline_id "${CI_PIPELINE_ID:-unknown}" \
  --arg commit_sha "${CI_COMMIT_SHA:-unknown}" \
  --arg branch "${CI_COMMIT_BRANCH:-unknown}" \
  --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  "${COUNT_FILES[@]}" 2>"$JQ_ERROR_FILE"); then

    # Extract problematic file from jq error message
    ERROR_MSG=$(cat "$JQ_ERROR_FILE")
    echo "ERROR: jq processing failed: $ERROR_MSG" >&2

    # Try to extract filename and job ID from error
    if [[ "$ERROR_MSG" =~ test_counts_([0-9]+)\.json ]]; then
        problem_job_id="${BASH_REMATCH[1]}"
        problem_file="./test_counts_${problem_job_id}.json"

        echo "" >&2
        echo "⚠️  Problematic file: $problem_file" >&2

        if [ -n "$GITLAB_BASE_URL" ]; then
            artifact_url="${GITLAB_BASE_URL}/-/jobs/${problem_job_id}/artifacts/file/test_counts_${problem_job_id}.json"
            echo "   Artifact URL: $artifact_url" >&2
        fi

        # Show a snippet of the problematic file
        if [ -f "$problem_file" ]; then
            echo "" >&2
            echo "File contents around the error:" >&2
            head -20 "$problem_file" >&2
        fi
    fi

    exit 1
fi

# Extract summary values
TOTAL_TESTS_ALL=$(echo "$AGGREGATED_DATA" | jq -r '.summary.total_tests')
TOTAL_PASSED_ALL=$(echo "$AGGREGATED_DATA" | jq -r '.summary.total_passed')
TOTAL_FAILED_ALL=$(echo "$AGGREGATED_DATA" | jq -r '.summary.total_failed')
TOTAL_SKIPPED_ALL=$(echo "$AGGREGATED_DATA" | jq -r '.summary.total_skipped')
ZERO_TEST_COUNT=$(echo "$AGGREGATED_DATA" | jq -r '.zero_test_jobs | length')

log_verbose "Overall totals: $TOTAL_TESTS_ALL tests ($TOTAL_PASSED_ALL passed, $TOTAL_FAILED_ALL failed, $TOTAL_SKIPPED_ALL skipped)"
log_verbose "Jobs with zero tests: $ZERO_TEST_COUNT"

# ANSI color codes
BOLD='\033[1m'
GREEN='\033[32m'
RED='\033[31m'
YELLOW='\033[33m'
CYAN='\033[36m'
GRAY='\033[90m'
RESET='\033[0m'

# Color based on values
FAILED_COLOR=$GRAY
SKIPPED_COLOR=$GRAY
[ "$TOTAL_FAILED_ALL" -gt 0 ] && FAILED_COLOR=$RED
[ "$TOTAL_SKIPPED_ALL" -gt 0 ] && SKIPPED_COLOR=$YELLOW

echo ""
echo -e "${CYAN}${BOLD}Pipeline Test Summary:${RESET}"
echo -e "  ${BOLD}Total:${RESET}   $TOTAL_TESTS_ALL"
echo -e "  ${GREEN}Passed:${RESET}  $TOTAL_PASSED_ALL"
echo -e "  ${FAILED_COLOR}Failed:${RESET}  $TOTAL_FAILED_ALL"
echo -e "  ${SKIPPED_COLOR}Skipped:${RESET} $TOTAL_SKIPPED_ALL"
echo ""

# Verbose logging for each job with artifact links
if [ $VERBOSE -eq 1 ]; then
    echo "$AGGREGATED_DATA" | jq -r --arg base_url "$GITLAB_BASE_URL" '.test_jobs[] |
        "  → Job: \(.ci_job_name) | Tests: \(.total_tests) (passed: \(.passed_tests), failed: \(.failed_tests), skipped: \(.skipped_tests))\n    Artifact: \($base_url)/-/jobs/\(.ci_job_id)/artifacts/file/test_counts_\(.ci_job_id).json"' >&2
fi

# Write JSON summary (just extract the parts we need)
log_verbose "Creating summary JSON file"
echo "$AGGREGATED_DATA" | jq '{
  pipeline_id,
  commit_sha,
  branch,
  timestamp,
  test_jobs,
  summary
}' > "$OUTPUT_FILE"

echo "Summary written to $OUTPUT_FILE"

# Create markdown report
log_verbose "Generating markdown report"
cat > "$REPORT_FILE" <<EOF
# Test Count Report

**Pipeline ID:** ${CI_PIPELINE_ID:-unknown}
**Commit:** ${CI_COMMIT_SHA:-unknown}
**Branch:** ${CI_COMMIT_BRANCH:-unknown}
**Date:** $(date -u +"%Y-%m-%d %H:%M:%S UTC")

## Overall Summary

| Metric | Count |
|--------|-------|
| Total Tests | $TOTAL_TESTS_ALL |
| Passed | $TOTAL_PASSED_ALL |
| Failed | $TOTAL_FAILED_ALL |
| Skipped | $TOTAL_SKIPPED_ALL |

## Breakdown by Test Category and JVM Version

| Test Category | JVM Version | Job Name | Total | Passed | Failed | Skipped |
|---------------|-------------|----------|-------|--------|--------|---------|
EOF

# Extract and write table rows from jq output
echo "$AGGREGATED_DATA" | jq -r '.table_rows[]' >> "$REPORT_FILE"

cat >> "$REPORT_FILE" <<EOF

## Alerts

EOF

# Check for zero test counts
log_verbose "Checking for alerts"
ALERT_COUNT=0

if [ "$TOTAL_TESTS_ALL" -eq 0 ]; then
    echo "⚠️ **CRITICAL**: No tests were executed in this pipeline!" >> "$REPORT_FILE"
    log_verbose "ALERT: Zero tests in entire pipeline!"
    ALERT_COUNT=$((ALERT_COUNT + 1))
fi

# Extract and write zero-test alerts from jq output
if [ "$ZERO_TEST_COUNT" -gt 0 ]; then
    echo "$AGGREGATED_DATA" | jq -r '.zero_test_jobs[].alert' >> "$REPORT_FILE"
    ALERT_COUNT=$((ALERT_COUNT + ZERO_TEST_COUNT))

    if [ $VERBOSE -eq 1 ]; then
        echo "$AGGREGATED_DATA" | jq -r '.zero_test_jobs[] | "ALERT: Zero tests in job '"'"'\(.job)'"'"' (\(.category), JVM \(.jvm))"' >&2
    fi
fi

log_verbose "Found $ALERT_COUNT alerts ($ZERO_TEST_COUNT jobs with zero tests)"

echo "" >> "$REPORT_FILE"
echo "---" >> "$REPORT_FILE"
echo "*This report is automatically generated. See [test-coverage.md](../docs/test-coverage.md) for details.*" >> "$REPORT_FILE"

echo "Report written to $REPORT_FILE"
cat "$REPORT_FILE"
