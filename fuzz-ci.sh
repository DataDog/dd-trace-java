#!/bin/bash

##############################################################################
# DD-Trace-Java Fuzzer - CI/CD Integration Script
# 
# This script can be used in CI/CD pipelines to run configuration fuzzing
# as part of automated testing.
##############################################################################

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Configuration - adjust these for your CI environment
ITERATIONS="${FUZZ_ITERATIONS:-20}"
JAVA_CMD="${FUZZ_JAVA_CMD:-java -jar app.jar}"
FAILURE_THRESHOLD="${FUZZ_FAILURE_THRESHOLD:-10}"  # Maximum % of failures allowed

# Colors (disabled in non-interactive mode)
if [ -t 1 ]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    BLUE='\033[0;34m'
    NC='\033[0m'
else
    RED=''
    GREEN=''
    YELLOW=''
    BLUE=''
    NC=''
fi

echo "=========================================="
echo "DD-Trace-Java Fuzzer - CI/CD Mode"
echo "=========================================="
echo ""
echo "Configuration:"
echo "  Iterations: $ITERATIONS"
echo "  Command: $JAVA_CMD"
echo "  Failure threshold: ${FAILURE_THRESHOLD}%"
echo ""

# Check prerequisites
if ! command -v jq &> /dev/null; then
    echo -e "${RED}Error: jq is not installed${NC}"
    echo "Install it before running this script:"
    echo "  Ubuntu/Debian: apt-get install jq"
    echo "  CentOS/RHEL: yum install jq"
    echo "  macOS: brew install jq"
    exit 1
fi

if [ ! -f "${SCRIPT_DIR}/fuzz-configs.sh" ]; then
    echo -e "${RED}Error: fuzz-configs.sh not found in ${SCRIPT_DIR}${NC}"
    exit 1
fi

# Run fuzzer
echo "Starting fuzz testing..."
echo ""

if "${SCRIPT_DIR}/fuzz-configs.sh" "$ITERATIONS" "$JAVA_CMD"; then
    FUZZ_EXIT_CODE=0
else
    FUZZ_EXIT_CODE=$?
fi

echo ""
echo "=========================================="
echo "Analyzing Results"
echo "=========================================="
echo ""

# Analyze results
LOG_DIR="${SCRIPT_DIR}/fuzz-logs"
if [ ! -d "$LOG_DIR" ]; then
    echo -e "${RED}Error: Log directory not found${NC}"
    exit 1
fi

# Count successes and failures
TOTAL_LOGS=$(find "$LOG_DIR" -name "fuzz_run_*.log" | wc -l)
SUCCESSFUL_RUNS=0
FAILED_RUNS=0

for log_file in "$LOG_DIR"/fuzz_run_*.log; do
    if grep -q "Test completed\|✓\|SUCCESS\|Started" "$log_file" 2>/dev/null; then
        ((SUCCESSFUL_RUNS++))
    else
        ((FAILED_RUNS++))
    fi
done

if [ "$TOTAL_LOGS" -gt 0 ]; then
    FAILURE_RATE=$((FAILED_RUNS * 100 / TOTAL_LOGS))
else
    FAILURE_RATE=0
fi

echo "Results:"
echo "  Total runs: $TOTAL_LOGS"
echo "  Successful: $SUCCESSFUL_RUNS"
echo "  Failed: $FAILED_RUNS"
echo "  Failure rate: ${FAILURE_RATE}%"
echo ""

# Check against threshold
if [ "$FAILURE_RATE" -gt "$FAILURE_THRESHOLD" ]; then
    echo -e "${RED}✗ FAILED: Failure rate (${FAILURE_RATE}%) exceeds threshold (${FAILURE_THRESHOLD}%)${NC}"
    echo ""
    echo "Failed runs:"
    for log_file in "$LOG_DIR"/fuzz_run_*.log; do
        if ! grep -q "Test completed\|✓\|SUCCESS\|Started" "$log_file" 2>/dev/null; then
            echo "  - $(basename "$log_file")"
            echo "    Configuration:"
            grep "^DD_" "$log_file" | grep -v "^#" | head -5 | sed 's/^/      /'
        fi
    done
    echo ""
    echo "For detailed analysis, review logs in: $LOG_DIR"
    exit 1
else
    echo -e "${GREEN}✓ PASSED: Failure rate (${FAILURE_RATE}%) is within threshold (${FAILURE_THRESHOLD}%)${NC}"
    
    if [ "$FAILED_RUNS" -gt 0 ]; then
        echo ""
        echo -e "${YELLOW}Note: $FAILED_RUNS run(s) failed but within acceptable threshold${NC}"
    fi
    
    exit 0
fi

