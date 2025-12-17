#!/bin/bash

##############################################################################
# DD-Trace-Java Fuzzer Log Analyzer
# 
# Analyzes fuzz test logs to identify patterns in failures and provide
# insights into which configurations might be causing issues.
##############################################################################

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${SCRIPT_DIR}/fuzz-logs"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  DD-Trace-Java Fuzzer Log Analyzer${NC}"
echo -e "${BLUE}==================================================================${NC}"
echo ""

# Check if log directory exists
if [ ! -d "$LOG_DIR" ]; then
    echo -e "${RED}Error: Log directory not found: $LOG_DIR${NC}"
    echo "Run the fuzzer first: ./fuzz-configs.sh <iterations> <command>"
    exit 1
fi

# Count log files
LOG_COUNT=$(find "$LOG_DIR" -name "fuzz_run_*.log" | wc -l)

if [ "$LOG_COUNT" -eq 0 ]; then
    echo -e "${RED}No log files found in $LOG_DIR${NC}"
    exit 1
fi

echo -e "${GREEN}Found $LOG_COUNT fuzz run logs${NC}"
echo ""

##############################################################################
# Analyze runs
##############################################################################

echo -e "${BLUE}Analyzing runs...${NC}"
echo ""

successful_runs=0
failed_runs=0
total_params_used=()
all_params=()

for log_file in "$LOG_DIR"/fuzz_run_*.log; do
    # Check for success indicators in the log
    if grep -q "Test completed\|✓\|SUCCESS\|Started" "$log_file" 2>/dev/null; then
        ((successful_runs++))
    else
        ((failed_runs++))
        echo -e "${RED}Failed run: $(basename "$log_file")${NC}"
        
        # Extract and display the configuration that failed
        echo -e "${YELLOW}Configuration:${NC}"
        grep "^DD_" "$log_file" | grep -v "^#" | head -20
        echo ""
    fi
    
    # Extract parameter names
    params=$(grep "^DD_" "$log_file" | grep -v "^#" | cut -d'=' -f1)
    for param in $params; do
        all_params+=("$param")
    done
    
    param_count=$(echo "$params" | wc -l)
    total_params_used+=($param_count)
done

##############################################################################
# Statistics
##############################################################################

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  Statistics${NC}"
echo -e "${BLUE}==================================================================${NC}"

echo -e "Total runs:          ${BLUE}$LOG_COUNT${NC}"
echo -e "Successful runs:     ${GREEN}$successful_runs${NC}"
echo -e "Failed runs:         ${RED}$failed_runs${NC}"

if [ $LOG_COUNT -gt 0 ]; then
    success_rate=$((successful_runs * 100 / LOG_COUNT))
    echo -e "Success rate:        ${GREEN}${success_rate}%${NC}"
fi

echo ""

##############################################################################
# Parameter frequency analysis
##############################################################################

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  Most Frequently Used Parameters${NC}"
echo -e "${BLUE}==================================================================${NC}"

if [ ${#all_params[@]} -gt 0 ]; then
    # Count parameter occurrences
    printf '%s\n' "${all_params[@]}" | sort | uniq -c | sort -rn | head -20 | while read count param; do
        echo -e "  ${GREEN}$count${NC} times: $param"
    done
else
    echo "No parameters found in logs"
fi

echo ""

##############################################################################
# Recommendations
##############################################################################

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  Recommendations${NC}"
echo -e "${BLUE}==================================================================${NC}"

if [ $failed_runs -eq 0 ]; then
    echo -e "${GREEN}✓ All runs completed successfully!${NC}"
    echo ""
    echo "Consider:"
    echo "  - Increasing the number of iterations"
    echo "  - Testing with more parameters per run"
    echo "  - Running with your actual application under load"
elif [ $failed_runs -lt $((LOG_COUNT / 10)) ]; then
    echo -e "${YELLOW}⚠ Less than 10% of runs failed${NC}"
    echo ""
    echo "Actions:"
    echo "  1. Review the failed run logs above"
    echo "  2. Check for common parameters across failures"
    echo "  3. Consider if failures are due to incompatible parameter combinations"
else
    echo -e "${RED}⚠ More than 10% of runs failed${NC}"
    echo ""
    echo "Urgent actions:"
    echo "  1. Review application logs for errors"
    echo "  2. Check if specific parameters are causing issues"
    echo "  3. Verify the application can start with basic configurations"
    echo "  4. Consider running with fewer parameters per iteration"
fi

echo ""

##############################################################################
# Recent runs
##############################################################################

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  Recent Runs (last 5)${NC}"
echo -e "${BLUE}==================================================================${NC}"

find "$LOG_DIR" -name "fuzz_run_*.log" -type f -print0 | \
    xargs -0 ls -t | head -5 | while read log_file; do
    echo -e "${YELLOW}$(basename "$log_file")${NC}"
    echo "  Timestamp: $(grep "^# Timestamp:" "$log_file" | cut -d' ' -f3)"
    echo "  Parameters: $(grep -c "^DD_" "$log_file" | grep -v "^#")"
    
    # Check status
    if grep -q "Test completed\|✓\|SUCCESS\|Started" "$log_file" 2>/dev/null; then
        echo -e "  Status: ${GREEN}Success${NC}"
    else
        echo -e "  Status: ${RED}Failed/Timeout${NC}"
    fi
    echo ""
done

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}For detailed analysis, review individual logs in: $LOG_DIR${NC}"
echo -e "${BLUE}==================================================================${NC}"

