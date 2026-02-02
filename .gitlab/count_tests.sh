#!/usr/bin/env bash

set -e

# Count tests from JUnit XML reports and emit metrics
# Usage: count_tests.sh [-v] <test_category> <jvm_version> <results_dir> <output_file>

# https://docs.gitlab.com/ci/variables/predefined_variables/

VERBOSE=0
if [ "$1" = "-v" ]; then
    VERBOSE=1
    shift
fi

TEST_CATEGORY="${1:-unknown}"
JVM_VERSION="${2:-unknown}"
RESULTS_DIR="${3:-./results}"
OUTPUT_FILE="${4:-./test_counts.json}"

log_verbose() {
    if [ $VERBOSE -eq 1 ]; then
        echo "[count_tests] $*" >&2
    fi
}

log_verbose "Job: ${CI_JOB_NAME:-unknown} (ID: ${CI_JOB_ID:-unknown})"
log_verbose "Test category: $TEST_CATEGORY"
log_verbose "JVM version: $JVM_VERSION"
log_verbose "Results directory: $RESULTS_DIR"
log_verbose "Output file: $OUTPUT_FILE"

if [ ! -d "$RESULTS_DIR" ]; then
    echo "Results directory not found: $RESULTS_DIR"
    log_verbose "Directory does not exist, exiting"
    exit 0
fi

# Count tests from JUnit XML files
TOTAL_TESTS=0
TOTAL_FAILURES=0
TOTAL_ERRORS=0
TOTAL_SKIPPED=0
XML_FILE_COUNT=0

echo "Counting tests in $RESULTS_DIR for $TEST_CATEGORY on JVM $JVM_VERSION"

# Find all XML files and count tests
log_verbose "Searching for XML files in $RESULTS_DIR"
for xml_file in $(find "$RESULTS_DIR" -name "*.xml" -type f 2>/dev/null); do
    XML_FILE_COUNT=$((XML_FILE_COUNT + 1))
    log_verbose "Processing file $XML_FILE_COUNT: $xml_file"
    if [ -f "$xml_file" ]; then
        # Extract test counts from testsuite tags
        # Handle both <testsuite> and <testsuites> formats
        tests=$(grep -oP '(?<=tests=")[0-9]+' "$xml_file" 2>/dev/null | head -1 || echo "0")
        failures=$(grep -oP '(?<=failures=")[0-9]+' "$xml_file" 2>/dev/null | head -1 || echo "0")
        errors=$(grep -oP '(?<=errors=")[0-9]+' "$xml_file" 2>/dev/null | head -1 || echo "0")
        skipped=$(grep -oP '(?<=skipped=")[0-9]+' "$xml_file" 2>/dev/null | head -1 || echo "0")

        log_verbose "  → tests=$tests, failures=$failures, errors=$errors, skipped=$skipped"

        TOTAL_TESTS=$((TOTAL_TESTS + tests))
        TOTAL_FAILURES=$((TOTAL_FAILURES + failures))
        TOTAL_ERRORS=$((TOTAL_ERRORS + errors))
        TOTAL_SKIPPED=$((TOTAL_SKIPPED + skipped))
    fi
done

log_verbose "Processed $XML_FILE_COUNT XML files"

TOTAL_PASSED=$((TOTAL_TESTS - TOTAL_FAILURES - TOTAL_ERRORS - TOTAL_SKIPPED))

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
ERRORS_COLOR=$GRAY
SKIPPED_COLOR=$GRAY
[ $TOTAL_FAILURES -gt 0 ] && FAILED_COLOR=$RED
[ $TOTAL_ERRORS -gt 0 ] && ERRORS_COLOR=$RED
[ $TOTAL_SKIPPED -gt 0 ] && SKIPPED_COLOR=$YELLOW

echo -e "${CYAN}${BOLD}Test counts for $TEST_CATEGORY on JVM $JVM_VERSION:${RESET}"
echo -e "  ${BOLD}Total:${RESET}   $TOTAL_TESTS"
echo -e "  ${GREEN}Passed:${RESET}  $TOTAL_PASSED"
echo -e "  ${FAILED_COLOR}Failed:${RESET}  $TOTAL_FAILURES"
echo -e "  ${ERRORS_COLOR}Errors:${RESET}  $TOTAL_ERRORS"
echo -e "  ${SKIPPED_COLOR}Skipped:${RESET} $TOTAL_SKIPPED"

# Create JSON output
log_verbose "Writing JSON output to $OUTPUT_FILE"
cat > "$OUTPUT_FILE" <<EOF
{
  "test_category": "$TEST_CATEGORY",
  "jvm_version": "$JVM_VERSION",
  "ci_job_name": "${CI_JOB_NAME:-unknown}",
  "ci_job_id": "${CI_JOB_ID:-unknown}",
  "ci_pipeline_id": "${CI_PIPELINE_ID:-unknown}",
  "ci_commit_sha": "${CI_COMMIT_SHA:-unknown}",
  "ci_commit_branch": "${CI_COMMIT_BRANCH:-unknown}",
  "ci_node_index": "${CI_NODE_INDEX:-1}",
  "ci_node_total": "${CI_NODE_TOTAL:-1}",
  "total_tests": $TOTAL_TESTS,
  "passed_tests": $TOTAL_PASSED,
  "failed_tests": $TOTAL_FAILURES,
  "error_tests": $TOTAL_ERRORS,
  "skipped_tests": $TOTAL_SKIPPED,
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

echo "Test count output written to $OUTPUT_FILE"
