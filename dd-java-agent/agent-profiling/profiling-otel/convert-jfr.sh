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
MODULE_DIR="$SCRIPT_DIR"

# Fat jar location
FAT_JAR_DIR="$MODULE_DIR/build/libs"
FAT_JAR_PATTERN="$FAT_JAR_DIR/profiling-otel-*-cli.jar"

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
        # Try GNU stat first (Linux, or GNU coreutils on macOS)
        local size=$(stat -c %s "$file" 2>/dev/null)
        if [ -n "$size" ] && [ "$size" != "" ]; then
            echo "$size"
        else
            # Fall back to BSD stat (macOS native)
            stat -f %z "$file" 2>/dev/null || echo "0"
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

# Get modification time of a file (seconds since epoch)
get_mtime() {
    local file="$1"
    if [ ! -f "$file" ]; then
        echo "0"
        return
    fi

    # Try GNU stat first (Linux, or GNU coreutils on macOS)
    local mtime=$(stat -c %Y "$file" 2>/dev/null)
    if [ -n "$mtime" ] && [ "$mtime" != "" ]; then
        echo "$mtime"
    else
        # Fall back to BSD stat (macOS native)
        stat -f %m "$file" 2>/dev/null || echo "0"
    fi
}

# Find the most recent source file in src/main/java
find_newest_source() {
    local newest=0
    while IFS= read -r -d '' file; do
        local mtime=$(get_mtime "$file")
        if [ "$mtime" -gt "$newest" ]; then
            newest="$mtime"
        fi
    done < <(find "$MODULE_DIR/src/main/java" -type f -name "*.java" -print0 2>/dev/null)
    echo "$newest"
}

# Check if fat jar needs rebuilding
needs_rebuild() {
    # Find the fat jar
    local jar=$(ls -t $FAT_JAR_PATTERN 2>/dev/null | head -1)

    if [ -z "$jar" ] || [ ! -f "$jar" ]; then
        # Jar doesn't exist
        return 0
    fi

    # Get jar modification time
    local jar_mtime=$(get_mtime "$jar")

    # Get newest source file time
    local newest_source_mtime=$(find_newest_source)

    # Rebuild if any source is newer than jar
    if [ "$newest_source_mtime" -gt "$jar_mtime" ]; then
        return 0
    fi

    # No rebuild needed
    return 1
}

# Ensure fat jar is built and up-to-date
ensure_fat_jar() {
    local rebuild_needed=false
    if needs_rebuild; then
        rebuild_needed=true
    fi

    if [ "$rebuild_needed" = true ]; then
        log_info "Building fat jar (source files changed or jar missing)..." >&2
        cd "$PROJECT_ROOT"
        ./gradlew -q :dd-java-agent:agent-profiling:profiling-otel:shadowJar >/dev/null 2>&1
        if [ $? -ne 0 ]; then
            log_error "Failed to build fat jar" >&2
            exit 1
        fi
    fi

    # Find and return the fat jar path
    local jar=$(ls -t $FAT_JAR_PATTERN 2>/dev/null | head -1)
    if [ -z "$jar" ] || [ ! -f "$jar" ]; then
        log_error "Fat jar not found after build" >&2
        exit 1
    fi

    echo "$jar"
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
  - Uses a fat jar for fast execution (no Gradle overhead)
  - Automatically rebuilds jar if source files change
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

# Ensure fat jar is built and get its path
FAT_JAR=$(ensure_fat_jar)

# Run conversion using fat jar and capture output
# Suppress SLF4J warnings (it defaults to NOP logger which is fine for CLI)
CONVERTER_OUTPUT=$(java -jar "$FAT_JAR" "${CONVERTER_ARGS[@]}" 2>&1 | grep -vE "^SLF4J:|SLF4JServiceProvider")
CONVERTER_EXIT=${PIPESTATUS[0]}

if [ $CONVERTER_EXIT -eq 0 ]; then
    # Extract output file (last argument)
    OUTPUT_FILE="${CONVERTER_ARGS[-1]}"

    if [ "$SHOW_DIAGNOSTICS" = true ]; then
        # With diagnostics: show converter output plus enhanced metrics
        echo "$CONVERTER_OUTPUT"

        OUTPUT_SIZE=$(get_file_size_bytes "$OUTPUT_FILE")

        echo ""
        log_diagnostic "=== Enhanced Diagnostics ==="

        # Show size comparison with input
        if [ ${#INPUT_FILES[@]} -gt 0 ]; then
            RATIO=$(calc_compression_ratio $TOTAL_INPUT_SIZE $OUTPUT_SIZE)
            log_diagnostic "Input → Output: $(format_bytes $TOTAL_INPUT_SIZE) → $(format_bytes $OUTPUT_SIZE)"
            log_diagnostic "Compression: $RATIO of original"

            if [ "$OUTPUT_SIZE" -lt "$TOTAL_INPUT_SIZE" ]; then
                SAVINGS=$((TOTAL_INPUT_SIZE - OUTPUT_SIZE))
                SAVINGS_PCT=$(awk "BEGIN {printf \"%.1f%%\", (1 - $OUTPUT_SIZE/$TOTAL_INPUT_SIZE) * 100}")
                log_diagnostic "Space saved: $(format_bytes $SAVINGS) ($SAVINGS_PCT reduction)"
            fi
        fi
        echo ""
    else
        # Without diagnostics: concise output
        # Extract just the key info from converter output
        CONVERSION_TIME=$(echo "$CONVERTER_OUTPUT" | grep -o 'Time: [0-9]* ms' | grep -o '[0-9]*' | head -1)
        OUTPUT_SIZE=$(get_file_size_bytes "$OUTPUT_FILE")

        log_success "Converted: $OUTPUT_FILE ($(format_bytes $OUTPUT_SIZE), ${CONVERSION_TIME}ms)"
    fi
else
    echo "$CONVERTER_OUTPUT"
    log_error "Conversion failed with exit code $CONVERTER_EXIT"
    exit $CONVERTER_EXIT
fi
