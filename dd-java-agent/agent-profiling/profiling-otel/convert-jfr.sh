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
#   --help              Show this help message
#
# Examples:
#   ./convert-jfr.sh recording.jfr output.pb
#   ./convert-jfr.sh --json recording.jfr output.json
#   ./convert-jfr.sh --pretty recording.jfr output.json
#   ./convert-jfr.sh file1.jfr file2.jfr combined.pb

set -e

# Script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

show_help() {
    cat << EOF
JFR to OTLP Converter

Usage:
  $(basename "$0") [options] <input.jfr> [input2.jfr ...] <output.pb|output.json>

Options:
  --json              Output in JSON format instead of protobuf
  --pretty            Pretty-print JSON output (implies --json)
  --include-payload   Include original JFR payload in OTLP output
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

Notes:
  - Uses Gradle's convertJfr task under the hood
  - Automatically compiles if needed
  - Output format is detected from extension (.pb or .json)

EOF
}

# Parse arguments
if [ $# -eq 0 ] || [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    show_help
    exit 0
fi

# Convert all arguments to a space-separated string for Gradle --args
ARGS="$*"

log_info "Converting JFR to OTLP format..."
log_info "Arguments: $ARGS"

cd "$PROJECT_ROOT"

# Run Gradle task with arguments
if ./gradlew -q :dd-java-agent:agent-profiling:profiling-otel:convertJfr --args="$ARGS"; then
    # Extract output file (last argument)
    OUTPUT_FILE="${!#}"

    log_success "Conversion completed successfully!"

    if [ -f "$OUTPUT_FILE" ]; then
        SIZE=$(du -h "$OUTPUT_FILE" | cut -f1)
        log_info "Output file: $OUTPUT_FILE ($SIZE)"
    fi
else
    EXIT_CODE=$?
    log_error "Conversion failed with exit code $EXIT_CODE"
    exit $EXIT_CODE
fi
