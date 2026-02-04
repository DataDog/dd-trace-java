#!/usr/bin/env bash

set -e

# Aggregate test counts from all test jobs using jq for all processing
# Usage: aggregate_test_counts.sh [-v] [aggregate_dir]

# https://docs.gitlab.com/ci/variables/predefined_variables/

# Source GitLab utilities for section formatting
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/gitlab-utils.sh" ]; then
    source "$SCRIPT_DIR/gitlab-utils.sh"
fi

VERBOSE=0
if [ "$1" = "-v" ]; then
    VERBOSE=1
    shift
fi

AGGREGATE_DIR="${1:-./test_counts_aggregate}"
OUTPUT_FILE="test_counts_summary.json"
REPORT_FILE="test_counts_report.md"

# IMPORTANT: Heredocs in this function use <<- (with hyphen) to allow indentation in source code
# while producing unindented output. This requires TABS (not spaces) for leading whitespace.
# Using tabs is not a matter of preference or style; it's how the language is defined.

log_verbose() {
    if [ $VERBOSE -eq 1 ]; then
        echo "[aggregate] $*" >&2
    fi
}

find_and_validate_test_files() {
    local aggregate_dir="$1"
    local output_file="$2"

    log_verbose "Searching for test count files (test_counts_<jobid>.json)"

    # Find all test count files (exclude summary to avoid reprocessing previous runs)
    local -a found_files
    mapfile -t found_files < <(find "$aggregate_dir" -name "test_counts_*.json" -not -name "$output_file" -type f 2>/dev/null | sort)

    local job_count=${#found_files[@]}
    log_verbose "Found $job_count test count files"

    if [ "$job_count" -eq 0 ]; then
        echo "No test count files found" >&2
        return 1
    fi

    # Validate and filter out invalid JSON files
    log_verbose "Validating JSON files"
    local -a valid_files=()
    local invalid_count=0
    local gitlab_base_url="${CI_PROJECT_URL}"

    for file in "${found_files[@]}"; do
        # More thorough validation: try to parse the structure we expect
        if jq -e 'type == "object" and has("ci_job_id") and has("total_tests")' "$file" >/dev/null 2>&1; then
            valid_files+=("$file")
        else
            # Extract job ID from filename (e.g., test_counts_1234.json -> 1234)
            local filename
            filename=$(basename "$file")
            local job_id="${filename#test_counts_}"
            job_id="${job_id%.json}"
            local artifact_url="${gitlab_base_url}/-/jobs/${job_id}/artifacts/file/${filename}"

            echo "⚠️  WARNING: Skipping invalid/empty JSON file: $file" >&2
            echo "   Artifact URL: $artifact_url" >&2
            log_verbose "Invalid JSON file: $file ($artifact_url)"
            ((invalid_count++))
        fi
    done

    local valid_count=${#valid_files[@]}
    log_verbose "Valid files: $valid_count, Invalid files: $invalid_count"

    if [ "$valid_count" -eq 0 ]; then
        echo "ERROR: No valid test count files found (all $job_count files are invalid)" >&2
        return 1
    fi

    # Return valid files by printing them (caller will capture)
    printf '%s\n' "${valid_files[@]}"
    return 0
}

aggregate_test_data() {
    local -a count_files=("$@")

    log_verbose "Processing all files with jq"

    # Capture jq output and errors
    local jq_error_file
    jq_error_file=$(mktemp)
    trap 'rm -f "$jq_error_file"' RETURN

    # Aggregate data using jq (heredoc with <<- strips leading tabs)
    # Using tabs is not a matter of preference or style; it's how the language is defined.
    local jq_program
    jq_program=$(cat <<-'JQ'
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
	  by_jvm: (group_by(.jvm_version) | map({
	    jvm_version: .[0].jvm_version,
	    total_tests: (map(.total_tests) | add),
	    total_passed: (map(.passed_tests) | add),
	    total_failed: (map(.failed_tests) | add),
	    total_skipped: (map(.skipped_tests) | add),
	    job_count: length
	  }) | sort_by(
	    if .jvm_version == "stable" then 100
	    elif ((.jvm_version | gsub("[^0-9]"; "")) as $nums | $nums == "") then 0
	    else (.jvm_version | gsub("[^0-9]"; "") | tonumber)
	    end
	  )),
	  by_job_kind_and_jvm: (
	    # Extract base job name (before the matrix suffix like ": [8, 2/6]")
	    map(. + {job_kind: (.ci_job_name | split(":")[0])}) |
	    group_by([.job_kind, .jvm_version]) |
	    map({
	      job_kind: .[0].job_kind,
	      jvm_version: .[0].jvm_version,
	      total_tests: (map(.total_tests) | add),
	      total_passed: (map(.passed_tests) | add),
	      total_failed: (map(.failed_tests) | add),
	      total_skipped: (map(.skipped_tests) | add),
	      split_count: length
	    }) | sort_by([
	      .job_kind,
	      (if .jvm_version == "stable" then 100
	       elif ((.jvm_version | gsub("[^0-9]"; "")) as $nums | $nums == "") then 0
	       else (.jvm_version | gsub("[^0-9]"; "") | tonumber)
	       end)
	    ])
	  ),
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
	JQ
    )

    local aggregated_data
    if ! aggregated_data=$(jq -s "$jq_program" \
      --arg pipeline_id "${CI_PIPELINE_ID:-unknown}" \
      --arg commit_sha "${CI_COMMIT_SHA:-unknown}" \
      --arg branch "${CI_COMMIT_BRANCH:-unknown}" \
      --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      "${count_files[@]}" 2>"$jq_error_file"); then

        # Extract problematic file from jq error message
        local error_msg
        error_msg=$(cat "$jq_error_file")
        echo "ERROR: jq processing failed: $error_msg" >&2

        # Try to extract filename and job ID from error
        if [[ "$error_msg" =~ test_counts_([0-9]+)\.json ]]; then
            local problem_job_id="${BASH_REMATCH[1]}"
            local problem_file="./test_counts_${problem_job_id}.json"
            local gitlab_base_url="${CI_PROJECT_URL}"

            echo "" >&2
            echo "⚠️  Problematic file: $problem_file" >&2

            if [ -n "$gitlab_base_url" ]; then
                local artifact_url="${gitlab_base_url}/-/jobs/${problem_job_id}/artifacts/file/test_counts_${problem_job_id}.json"
                echo "   Artifact URL: $artifact_url" >&2
            fi

            # Show a snippet of the problematic file
            if [ -f "$problem_file" ]; then
                echo "" >&2
                echo "File contents around the error:" >&2
                head -20 "$problem_file" >&2
            fi
        fi

        return 1
    fi

    # Return aggregated data
    echo "$aggregated_data"
    return 0
}

display_summary() {
    local aggregated_data="$1"

    # Extract summary values
    local total_tests
    local total_passed
    local total_failed
    local total_skipped
    local zero_test_count

    total_tests=$(echo "$aggregated_data" | jq -r '.summary.total_tests')
    total_passed=$(echo "$aggregated_data" | jq -r '.summary.total_passed')
    total_failed=$(echo "$aggregated_data" | jq -r '.summary.total_failed')
    total_skipped=$(echo "$aggregated_data" | jq -r '.summary.total_skipped')
    zero_test_count=$(echo "$aggregated_data" | jq -r '.zero_test_jobs | length')

    log_verbose "Overall totals: $total_tests tests ($total_passed passed, $total_failed failed, $total_skipped skipped)"
    log_verbose "Jobs with zero tests: $zero_test_count"

    # ANSI color codes
    local bold='\033[1m'
    local green='\033[32m'
    local red='\033[31m'
    local yellow='\033[33m'
    local cyan='\033[36m'
    local gray='\033[90m'
    local reset='\033[0m'

    # Color based on values
    local failed_color=$gray
    local skipped_color=$gray
    [ "$total_failed" -gt 0 ] && failed_color=$red
    [ "$total_skipped" -gt 0 ] && skipped_color=$yellow

    echo ""
    echo -e "${cyan}${bold}Pipeline Test Summary:${reset}"
    echo -e "  ${bold}Total:${reset}   $total_tests"
    echo -e "  ${green}Passed:${reset}  $total_passed"
    echo -e "  ${failed_color}Failed:${reset}  $total_failed"
    echo -e "  ${skipped_color}Skipped:${reset} $total_skipped"
    echo ""

    # Links to Datadog CI Visibility and Test Optimization
    if [ -n "${CI_PIPELINE_ID}" ]; then
        echo -e "${bold}${yellow}See test results in Datadog:${reset}"
        echo -e "  ${cyan}CI Visibility:${reset} https://app.datadoghq.com/ci/test/runs?query=test_level%3Atest%20%40test.service%3Add-trace-java%20%40ci.pipeline.id%3A${CI_PIPELINE_ID}"
        echo -e "  ${cyan}Test Optimization:${reset} https://app.datadoghq.com/ci/settings/test-optimization?search=dd-trace-java"
        echo ""
    fi

    # Display alerts in log output
    if [ "$total_tests" -eq 0 ] || [ "$zero_test_count" -gt 0 ]; then
        echo -e "${red}${bold}Alerts:${reset}"

        if [ "$total_tests" -eq 0 ]; then
            echo -e "  ${red}🚨 CRITICAL: No tests were executed in this pipeline!${reset}"
        fi

        if [ "$zero_test_count" -gt 0 ]; then
            echo -e "  ${yellow}⚠️  WARNING: $zero_test_count job(s) with zero tests:${reset}"
            echo "$aggregated_data" | jq -r '.zero_test_jobs[] | "    • \(.job) (\(.category), JVM \(.jvm))"'
        fi

        echo ""
    fi
}

display_jvm_breakdown() {
    local aggregated_data="$1"

    gitlab_section_start "test-jvm-breakdown" "Test Breakdown by JVM Version"

    # Header
    printf "%-15s %12s %12s %12s %12s %10s\n" "JVM Version" "Total Tests" "Passed" "Failed" "Skipped" "Jobs" >&2
    printf "%-15s %12s %12s %12s %12s %10s\n" "---------------" "------------" "------------" "------------" "------------" "----------" >&2

    # Data rows
    echo "$aggregated_data" | jq -r '.by_jvm[] |
    "\(.jvm_version)|\(.total_tests)|\(.total_passed)|\(.total_failed)|\(.total_skipped)|\(.job_count)"' | while IFS='|' read -r jvm total passed failed skipped jobs; do
        printf "%-15s %12s %12s %12s %12s %10s\n" "$jvm" "$total" "$passed" "$failed" "$skipped" "$jobs" >&2
    done

    gitlab_section_end "test-jvm-breakdown"
}

display_job_kind_breakdown() {
    local aggregated_data="$1"

    gitlab_section_start "test-job-kind-breakdown" "Test Breakdown by Job Kind and JVM (ignoring splits)"

    # Header
    printf "%-40s %-15s %12s %12s %12s %12s %8s\n" "Job Kind" "JVM" "Total Tests" "Passed" "Failed" "Skipped" "Splits" >&2
    printf "%-40s %-15s %12s %12s %12s %12s %8s\n" "----------------------------------------" "---------------" "------------" "------------" "------------" "------------" "--------" >&2

    # Data rows
    echo "$aggregated_data" | jq -r '.by_job_kind_and_jvm[] |
    "\(.job_kind)|\(.jvm_version)|\(.total_tests)|\(.total_passed)|\(.total_failed)|\(.total_skipped)|\(.split_count)"' | while IFS='|' read -r job_kind jvm total passed failed skipped splits; do
        printf "%-40s %-15s %12s %12s %12s %12s %8s\n" "$job_kind" "$jvm" "$total" "$passed" "$failed" "$skipped" "$splits" >&2
    done

    gitlab_section_end "test-job-kind-breakdown"
}

display_detailed_results() {
    local aggregated_data="$1"
    local gitlab_base_url="${CI_PROJECT_URL}"

    gitlab_section_start "test-job-details" "Detailed Test Results by Job"
    echo "$aggregated_data" | jq -r --arg base_url "$gitlab_base_url" '.test_jobs[] |
    "  → Job: \(.ci_job_name) | Tests: \(.total_tests) (passed: \(.passed_tests), failed: \(.failed_tests), skipped: \(.skipped_tests))\n    Artifact: \($base_url)/-/jobs/\(.ci_job_id)/artifacts/file/test_counts_\(.ci_job_id).json"' >&2
    gitlab_section_end "test-job-details"
}

write_json_summary() {
    local aggregated_data="$1"
    local output_file="$2"

    log_verbose "Creating summary JSON file"
    echo "$aggregated_data" | jq '{
      pipeline_id,
      commit_sha,
      branch,
      timestamp,
      test_jobs,
      summary,
      by_jvm,
      by_job_kind_and_jvm
    }' > "$output_file"
    echo "Summary written to $output_file"
}

write_markdown_report() {
    local aggregated_data="$1"
    local report_file="$2"

    log_verbose "Generating markdown report"

    # Extract summary values
    local total_tests
    local total_passed
    local total_failed
    local total_skipped
    local zero_test_count

    total_tests=$(echo "$aggregated_data" | jq -r '.summary.total_tests')
    total_passed=$(echo "$aggregated_data" | jq -r '.summary.total_passed')
    total_failed=$(echo "$aggregated_data" | jq -r '.summary.total_failed')
    total_skipped=$(echo "$aggregated_data" | jq -r '.summary.total_skipped')
    zero_test_count=$(echo "$aggregated_data" | jq -r '.zero_test_jobs | length')

    # IMPORTANT: Heredocs in this function use <<- (with hyphen) to allow indentation in source code
    # while producing unindented output. This requires TABS (not spaces) for leading whitespace.
    # Using tabs is not a matter of preference or style; it's how the language is defined.

    # Write report header and summary table
    cat > "$report_file" <<-EOF  # <<- strips leading tabs from heredoc content
	# Test Count Report

	**Pipeline ID:** ${CI_PIPELINE_ID:-unknown}
	**Commit:** ${CI_COMMIT_SHA:-unknown}
	**Branch:** ${CI_COMMIT_BRANCH:-unknown}
	**Date:** $(date -u +"%Y-%m-%d %H:%M:%S UTC")

	## Overall Summary

	| Metric | Count |
	|--------|-------|
	| Total Tests | $total_tests |
	| Passed | $total_passed |
	| Failed | $total_failed |
	| Skipped | $total_skipped |

	## Breakdown by JVM Version

	| JVM Version | Total Tests | Passed | Failed | Skipped | Job Count |
	|-------------|-------------|--------|--------|---------|-----------|
	EOF

    # Write JVM breakdown rows
    echo "$aggregated_data" | jq -r '.by_jvm[] | "| \(.jvm_version) | \(.total_tests) | \(.total_passed) | \(.total_failed) | \(.total_skipped) | \(.job_count) |"' >> "$report_file"

    # Write job kind breakdown section
    cat >> "$report_file" <<-EOF

	## Breakdown by Job Kind and JVM Version

	| Job Kind | JVM Version | Total Tests | Passed | Failed | Skipped | Splits |
	|----------|-------------|-------------|--------|--------|---------|--------|
	EOF

    # Write job kind breakdown rows
    echo "$aggregated_data" | jq -r '.by_job_kind_and_jvm[] | "| \(.job_kind) | \(.jvm_version) | \(.total_tests) | \(.total_passed) | \(.total_failed) | \(.total_skipped) | \(.split_count) |"' >> "$report_file"

    # Write detailed breakdown section
    cat >> "$report_file" <<-EOF

	## Detailed Breakdown by Test Category and JVM Version

	| Test Category | JVM Version | Job Name | Total | Passed | Failed | Skipped |
	|---------------|-------------|----------|-------|--------|--------|---------|
	EOF

    # Write table rows
    echo "$aggregated_data" | jq -r '.table_rows[]' >> "$report_file"

    # Write alerts section
    cat >> "$report_file" <<-EOF  # <<- strips leading tabs

	## Alerts

	EOF

    local alert_count=0

    # Check for critical alert (no tests in entire pipeline)
    if [ "$total_tests" -eq 0 ]; then
        echo "⚠️ **CRITICAL**: No tests were executed in this pipeline!" >> "$report_file"
        log_verbose "ALERT: Zero tests in entire pipeline!"
        alert_count=$((alert_count + 1))
    fi

    # Check for zero-test job alerts
    if [ "$zero_test_count" -gt 0 ]; then
        echo "$aggregated_data" | jq -r '.zero_test_jobs[].alert' >> "$report_file"
        alert_count=$((alert_count + zero_test_count))

        if [ $VERBOSE -eq 1 ]; then
            echo "$aggregated_data" | jq -r '.zero_test_jobs[] | "ALERT: Zero tests in job '"'"'\(.job)'"'"' (\(.category), JVM \(.jvm))"' >&2
        fi
    fi

    log_verbose "Found $alert_count alerts ($zero_test_count jobs with zero tests)"

    # Write report footer
    cat >> "$report_file" <<-EOF  # <<- strips leading tabs

	---
	*This report is automatically generated. See [test-coverage.md](../docs/test-coverage.md) for details.*
	EOF

    echo "Report written to $report_file"
}

mkdir -p "$AGGREGATE_DIR"

echo "Aggregating test counts..."

# Log configuration details
while IFS= read -r line; do
    log_verbose "$line"
done <<-EOF  # <<- strips leading tabs
	Pipeline ID: ${CI_PIPELINE_ID:-unknown}
	Commit: ${CI_COMMIT_SHA:-unknown}
	Branch: ${CI_COMMIT_BRANCH:-unknown}
	Aggregate directory: $AGGREGATE_DIR
	Output file: $OUTPUT_FILE
	Report file: $REPORT_FILE
	EOF

# Find and validate test count files
mapfile -t VALID_FILES < <(find_and_validate_test_files "." "$OUTPUT_FILE")
if [ ${#VALID_FILES[@]} -eq 0 ]; then
    exit 0
fi

# Aggregate test data
if ! AGGREGATED_DATA=$(aggregate_test_data "${VALID_FILES[@]}"); then
    exit 1
fi

# Display summary and alerts in log
display_summary "$AGGREGATED_DATA"

# Display breakdowns in collapsible sections
display_jvm_breakdown "$AGGREGATED_DATA"
display_job_kind_breakdown "$AGGREGATED_DATA"

# Display detailed results in collapsible section
display_detailed_results "$AGGREGATED_DATA"

# Write reports
write_json_summary "$AGGREGATED_DATA" "$OUTPUT_FILE"
write_markdown_report "$AGGREGATED_DATA" "$REPORT_FILE"

# Only output markdown report in verbose mode
if [ $VERBOSE -eq 1 ]; then
    cat "$REPORT_FILE"
fi
