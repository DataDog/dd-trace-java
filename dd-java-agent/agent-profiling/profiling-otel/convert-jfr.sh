#!/usr/bin/env bash

# JFR to OTLP Converter Script
#
# This script provides a convenient wrapper around the Gradle-based JFR converter.
# It automatically handles the classpath and provides a simpler interface.
#
# Usage:
#   ./convert-jfr.sh [options] <input.jfr> [input2.jfr ...] <output.pb|output.json>
#
# Options:
#   --json              Output in JSON format instead of protobuf
#   --pretty            Pretty-print JSON output (implies --json)
#   --include-payload   Include original JFR payload in OTLP output
#   --diagnostics       Show detailed diagnostics (file sizes, conversion time)
#   --help              Show this help message
#
# Examples:
#   ./convert-jfr.sh recording.jfr output.pb
#   ./convert-jfr.sh --json recording.jfr output.json
#   ./convert-jfr.sh --pretty recording.jfr output.json
#   ./convert-jfr.sh --diagnostics file1.jfr file2.jfr combined.pb

set -e

# Script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_diagnostic() { echo -e "${CYAN}[DIAG]${NC} $1"; }

# Get file size in human-readable format
get_file_size() {
    local file="$1"
    if [ -f "$file" ]; then
        # Use du for cross-platform compatibility
        du -h "$file" | cut -f1
    else
        echo "N/A"
    fi
}

# Get file size in bytes
get_file_size_bytes() {
    local file="$1"
    if [ -f "$file" ]; then
        # Cross-platform file size in bytes
        if [[ "$OSTYPE" == "darwin"* ]]; then
            stat -f%z "$file"
        else
            stat -c%s "$file"
        fi
    else
        echo "0"
    fi
}

# Format bytes to human-readable
format_bytes() {
    local bytes=$1
    if [ "$bytes" -lt 1024 ]; then
        echo "${bytes}B"
    elif [ "$bytes" -lt 1048576 ]; then
        echo "$(awk "BEGIN {printf \"%.1f\", $bytes/1024}")KB"
    elif [ "$bytes" -lt 1073741824 ]; then
        echo "$(awk "BEGIN {printf \"%.1f\", $bytes/1048576}")MB"
    else
        echo "$(awk "BEGIN {printf \"%.1f\", $bytes/1073741824}")GB"
    fi
}

# Calculate compression ratio
calc_compression_ratio() {
    local input_size=$1
    local output_size=$2
    if [ "$input_size" -eq 0 ]; then
        echo "N/A"
    else
        awk "BEGIN {printf \"%.1f%%\", ($output_size / $input_size) * 100}"
    fi
}

show_help() {
    cat << EOF
JFR to OTLP Converter

Usage:
  $(basename "$0") [options] <input.jfr> [input2.jfr ...] <output.pb|output.json>

Options:
  --json              Output in JSON format instead of protobuf
  --pretty            Pretty-print JSON output (implies --json)
  --include-payload   Include original JFR payload in OTLP output
  --diagnostics       Show detailed diagnostics (file sizes, conversion time)
  --help              Show this help message

Examples:
  # Convert to protobuf (default)
  $(basename "$0") recording.jfr output.pb

  # Convert to JSON
  $(basename "$0") --json recording.jfr output.json

  # Convert to pretty JSON
  $(basename "$0") --pretty recording.jfr output.json

  # Include original JFR in output
  $(basename "$0") --include-payload recording.jfr output.pb

  # Combine multiple JFR files
  $(basename "$0") file1.jfr file2.jfr combined.pb

  # Show detailed diagnostics
  $(basename "$0") --diagnostics recording.jfr output.pb

Notes:
  - Uses Gradle's convertJfr task under the hood
  - Automatically compiles if needed
  - Output format is detected from extension (.pb or .json)
  - Use --diagnostics to see file sizes and conversion times

EOF
}

# Parse arguments
if [ $# -eq 0 ] || [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    show_help
    exit 0
fi

# Check for diagnostics flag
SHOW_DIAGNOSTICS=false
CONVERTER_ARGS=()
INPUT_FILES=()

while [[ $# -gt 0 ]]; do
    case $1 in
        --diagnostics)
            SHOW_DIAGNOSTICS=true
            shift
            ;;
        --json|--pretty|--include-payload)
            CONVERTER_ARGS+=("$1")
            shift
            ;;
        *)
            # Collect files
            CONVERTER_ARGS+=("$1")
            # If it's not the last arg and file exists, it's an input file
            if [ $# -gt 1 ] && [ -f "$1" ]; then
                INPUT_FILES+=("$1")
            fi
            shift
            ;;
    esac
done

# Convert all arguments to a space-separated string for Gradle --args
ARGS="${CONVERTER_ARGS[*]}"

# Calculate total input size if diagnostics enabled
TOTAL_INPUT_SIZE=0
if [ "$SHOW_DIAGNOSTICS" = true ]; then
    for input_file in "${INPUT_FILES[@]}"; do
        if [ -f "$input_file" ]; then
            size=$(get_file_size_bytes "$input_file")
            TOTAL_INPUT_SIZE=$((TOTAL_INPUT_SIZE + size))
            log_diagnostic "Input: $input_file ($(format_bytes $size))"
        fi
    done
    if [ ${#INPUT_FILES[@]} -gt 0 ]; then
        log_diagnostic "Total input size: $(format_bytes $TOTAL_INPUT_SIZE)"
    fi
fi

log_info "Converting JFR to OTLP format..."

cd "$PROJECT_ROOT"

# Measure conversion time
START_TIME=$(date +%s%N)
START_CPU=$(ps -o cputime= -p $$ | tr -d ' :')

# Run Gradle task with arguments
if ./gradlew -q :dd-java-agent:agent-profiling:profiling-otel:convertJfr --args="$ARGS"; then
    # Measure end time
    END_TIME=$(date +%s%N)
    END_CPU=$(ps -o cputime= -p $$ | tr -d ' :')

    # Extract output file (last argument)
    OUTPUT_FILE="${CONVERTER_ARGS[-1]}"

    log_success "Conversion completed successfully!"

    if [ -f "$OUTPUT_FILE" ]; then
        OUTPUT_SIZE=$(get_file_size_bytes "$OUTPUT_FILE")
        SIZE=$(format_bytes $OUTPUT_SIZE)
        log_info "Output file: $OUTPUT_FILE ($SIZE)"

        if [ "$SHOW_DIAGNOSTICS" = true ]; then
            echo ""
            log_diagnostic "=== Conversion Diagnostics ==="

            # Calculate wall time
            WALL_TIME_NS=$((END_TIME - START_TIME))
            WALL_TIME_MS=$(awk "BEGIN {printf \"%.1f\", $WALL_TIME_NS/1000000}")
            log_diagnostic "Wall time: ${WALL_TIME_MS}ms"

            # Show size comparison
            if [ ${#INPUT_FILES[@]} -gt 0 ]; then
                RATIO=$(calc_compression_ratio $TOTAL_INPUT_SIZE $OUTPUT_SIZE)
                log_diagnostic "Output size: $(format_bytes $OUTPUT_SIZE)"
                log_diagnostic "Size ratio: $RATIO of input"

                if [ "$OUTPUT_SIZE" -lt "$TOTAL_INPUT_SIZE" ]; then
                    SAVINGS=$((TOTAL_INPUT_SIZE - OUTPUT_SIZE))
                    SAVINGS_PCT=$(awk "BEGIN {printf \"%.1f%%\", (1 - $OUTPUT_SIZE/$TOTAL_INPUT_SIZE) * 100}")
                    log_diagnostic "Savings: $(format_bytes $SAVINGS) ($SAVINGS_PCT reduction)"
                fi
            fi

            echo ""
        fi
    fi
else
    EXIT_CODE=$?
    log_error "Conversion failed with exit code $EXIT_CODE"
    exit $EXIT_CODE
fi
