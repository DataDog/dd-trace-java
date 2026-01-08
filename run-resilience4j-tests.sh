#!/bin/bash
#
# Resilience4j Comprehensive Instrumentation Test Runner
# Created: 2026-01-08
#

set -e

MODULE_PATH="dd-java-agent:instrumentation:resilience4j:resilience4j-comprehensive"
REPO_ROOT="/Users/junaidahmed/dd-trace-java"

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  Resilience4j Comprehensive Instrumentation - Test Suite${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo ""

cd "$REPO_ROOT"

# Check Java version
echo -e "${YELLOW}Checking Java version...${NC}"
if ! java -version 2>&1 | grep -q "version"; then
    echo -e "${RED}ERROR: Java not found. Please install Java 17 or higher.${NC}"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo -e "${RED}ERROR: Java 17+ required. Found version: $JAVA_VERSION${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Java version OK${NC}"
echo ""

# Function to run a specific test
run_test() {
    local test_name=$1
    echo -e "${YELLOW}Running ${test_name}...${NC}"
    if ./gradlew :$MODULE_PATH:test --tests "*${test_name}" --console=plain 2>&1 | tee /tmp/${test_name}.log; then
        echo -e "${GREEN}✓ ${test_name} PASSED${NC}"
        return 0
    else
        echo -e "${RED}✗ ${test_name} FAILED${NC}"
        return 1
    fi
}

# Parse command line arguments
if [ "$1" == "--help" ] || [ "$1" == "-h" ]; then
    echo "Usage: $0 [OPTIONS] [TEST_NAME]"
    echo ""
    echo "Options:"
    echo "  --all             Run all tests (default)"
    echo "  --quick           Run only RateLimiter and Bulkhead tests"
    echo "  --component NAME  Run specific component test"
    echo "  --build           Build before testing"
    echo "  --clean           Clean before testing"
    echo "  --report          Generate HTML test report"
    echo "  --help            Show this help"
    echo ""
    echo "Available test components:"
    echo "  RateLimiterTest"
    echo "  BulkheadTest"
    echo "  ThreadPoolBulkheadTest"
    echo "  TimeLimiterTest"
    echo "  CircuitBreakerTest"
    echo "  RetryTest"
    echo ""
    echo "Examples:"
    echo "  $0 --all"
    echo "  $0 --component RateLimiterTest"
    echo "  $0 --build --all"
    exit 0
fi

# Parse options
DO_BUILD=false
DO_CLEAN=false
DO_REPORT=false
TEST_MODE="all"
SPECIFIC_TEST=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --build)
            DO_BUILD=true
            shift
            ;;
        --clean)
            DO_CLEAN=true
            shift
            ;;
        --report)
            DO_REPORT=true
            shift
            ;;
        --all)
            TEST_MODE="all"
            shift
            ;;
        --quick)
            TEST_MODE="quick"
            shift
            ;;
        --component)
            TEST_MODE="specific"
            SPECIFIC_TEST="$2"
            shift 2
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

# Clean if requested
if [ "$DO_CLEAN" == true ]; then
    echo -e "${YELLOW}Cleaning build...${NC}"
    ./gradlew :$MODULE_PATH:clean
    echo -e "${GREEN}✓ Clean complete${NC}"
    echo ""
fi

# Build if requested
if [ "$DO_BUILD" == true ]; then
    echo -e "${YELLOW}Building module...${NC}"
    if ./gradlew :$MODULE_PATH:build -x test; then
        echo -e "${GREEN}✓ Build successful${NC}"
    else
        echo -e "${RED}✗ Build failed${NC}"
        exit 1
    fi
    echo ""
fi

# Run tests based on mode
FAILED_TESTS=()
PASSED_TESTS=()

case $TEST_MODE in
    "specific")
        echo -e "${BLUE}Running specific test: ${SPECIFIC_TEST}${NC}"
        echo ""
        if run_test "$SPECIFIC_TEST"; then
            PASSED_TESTS+=("$SPECIFIC_TEST")
        else
            FAILED_TESTS+=("$SPECIFIC_TEST")
        fi
        ;;

    "quick")
        echo -e "${BLUE}Running quick test suite (RateLimiter + Bulkhead)${NC}"
        echo ""
        for test in "RateLimiterTest" "BulkheadTest"; do
            if run_test "$test"; then
                PASSED_TESTS+=("$test")
            else
                FAILED_TESTS+=("$test")
            fi
            echo ""
        done
        ;;

    "all")
        echo -e "${BLUE}Running full test suite (all 6 components)${NC}"
        echo ""

        TESTS=(
            "RateLimiterTest"
            "BulkheadTest"
            "ThreadPoolBulkheadTest"
            "TimeLimiterTest"
            "CircuitBreakerTest"
            "RetryTest"
        )

        for test in "${TESTS[@]}"; do
            if run_test "$test"; then
                PASSED_TESTS+=("$test")
            else
                FAILED_TESTS+=("$test")
            fi
            echo ""
        done
        ;;
esac

# Generate HTML report if requested
if [ "$DO_REPORT" == true ]; then
    echo -e "${YELLOW}Generating HTML test report...${NC}"
    ./gradlew :$MODULE_PATH:test --console=plain > /dev/null 2>&1 || true
    REPORT_PATH="$REPO_ROOT/dd-java-agent/instrumentation/resilience4j/resilience4j-comprehensive/build/reports/tests/test/index.html"
    if [ -f "$REPORT_PATH" ]; then
        echo -e "${GREEN}✓ Report generated: ${REPORT_PATH}${NC}"
        echo -e "${BLUE}  Open with: open ${REPORT_PATH}${NC}"
    else
        echo -e "${RED}✗ Report not found${NC}"
    fi
    echo ""
fi

# Print summary
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  Test Summary${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
echo ""

if [ ${#PASSED_TESTS[@]} -gt 0 ]; then
    echo -e "${GREEN}Passed Tests (${#PASSED_TESTS[@]}):${NC}"
    for test in "${PASSED_TESTS[@]}"; do
        echo -e "  ${GREEN}✓${NC} $test"
    done
    echo ""
fi

if [ ${#FAILED_TESTS[@]} -gt 0 ]; then
    echo -e "${RED}Failed Tests (${#FAILED_TESTS[@]}):${NC}"
    for test in "${FAILED_TESTS[@]}"; do
        echo -e "  ${RED}✗${NC} $test"
        echo -e "    Log: /tmp/${test}.log"
    done
    echo ""
fi

TOTAL_TESTS=$((${#PASSED_TESTS[@]} + ${#FAILED_TESTS[@]}))
echo -e "Total: ${TOTAL_TESTS} tests"
echo -e "Passed: ${GREEN}${#PASSED_TESTS[@]}${NC}"
echo -e "Failed: ${RED}${#FAILED_TESTS[@]}${NC}"
echo ""

# Exit with appropriate code
if [ ${#FAILED_TESTS[@]} -gt 0 ]; then
    echo -e "${RED}Some tests failed!${NC}"
    exit 1
else
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
fi
