#!/bin/bash

##############################################################################
# DD-Trace-Java Configuration Type Reporter
# 
# Analyzes all configuration parameters and reports their detected types
# based on naming patterns used by the fuzzer.
##############################################################################

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${SCRIPT_DIR}/metadata/supported-configurations.json"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  DD-Trace-Java Configuration Type Report${NC}"
echo -e "${BLUE}==================================================================${NC}"
echo ""

# Check if config file exists
if [ ! -f "$CONFIG_FILE" ]; then
    echo -e "${RED}Error: Configuration file not found: $CONFIG_FILE${NC}"
    exit 1
fi

# Extract all configuration keys
CONFIGS=($(jq -r '.supportedConfigurations | keys[]' "$CONFIG_FILE"))
TOTAL=${#CONFIGS[@]}

echo -e "${GREEN}Analyzing $TOTAL configuration parameters...${NC}"
echo ""

# Initialize counters
boolean_count=0
integer_count=0
float_count=0
string_count=0

boolean_params=()
integer_params=()
float_params=()
string_params=()

##############################################################################
# Classify each parameter
##############################################################################

for param in "${CONFIGS[@]}"; do
    # Determine type based on parameter name patterns (same logic as fuzzer)
    if [[ "$param" =~ ENABLED$|^DD_TRACE_ENABLED$|DEBUG$|COLLECT|HEADER_COLLECTION|REPORTING|SPLIT_BY ]]; then
        ((boolean_count++))
        boolean_params+=("$param")
    elif [[ "$param" =~ PORT$|TIMEOUT$|DELAY$|SIZE$|LIMIT$|MAX_|DEPTH$|COUNT$|QUEUE_SIZE$|BUFFER ]]; then
        ((integer_count++))
        integer_params+=("$param")
    elif [[ "$param" =~ SAMPLE_RATE$|_RATE$ ]] && [[ ! "$param" =~ TRACE_RATE_LIMIT ]]; then
        ((float_count++))
        float_params+=("$param")
    elif [[ "$param" =~ INTERVAL$ ]]; then
        if [[ "$param" =~ FLUSH_INTERVAL ]]; then
            ((float_count++))
            float_params+=("$param")
        else
            ((integer_count++))
            integer_params+=("$param")
        fi
    else
        ((string_count++))
        string_params+=("$param")
    fi
done

##############################################################################
# Display Summary
##############################################################################

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  Type Distribution${NC}"
echo -e "${BLUE}==================================================================${NC}"

echo -e "${GREEN}Boolean parameters:${NC} $boolean_count ($(( boolean_count * 100 / TOTAL ))%)"
echo -e "${GREEN}Integer parameters:${NC} $integer_count ($(( integer_count * 100 / TOTAL ))%)"
echo -e "${GREEN}Float parameters:${NC}   $float_count ($(( float_count * 100 / TOTAL ))%)"
echo -e "${GREEN}String parameters:${NC}  $string_count ($(( string_count * 100 / TOTAL ))%)"
echo ""

##############################################################################
# Display samples
##############################################################################

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  Sample Parameters by Type${NC}"
echo -e "${BLUE}==================================================================${NC}"

echo -e "${YELLOW}Boolean Parameters (sample of ${boolean_count}):${NC}"
printf '%s\n' "${boolean_params[@]}" | head -10
if [ ${#boolean_params[@]} -gt 10 ]; then
    echo "  ... and $((boolean_count - 10)) more"
fi
echo ""

echo -e "${YELLOW}Integer Parameters (sample of ${integer_count}):${NC}"
printf '%s\n' "${integer_params[@]}" | head -10
if [ ${#integer_params[@]} -gt 10 ]; then
    echo "  ... and $((integer_count - 10)) more"
fi
echo ""

echo -e "${YELLOW}Float Parameters (sample of ${float_count}):${NC}"
printf '%s\n' "${float_params[@]}" | head -10
if [ ${#float_params[@]} -gt 10 ]; then
    echo "  ... and $((float_count - 10)) more"
fi
echo ""

echo -e "${YELLOW}String Parameters (sample of ${string_count}):${NC}"
printf '%s\n' "${string_params[@]}" | head -20
if [ ${#string_params[@]} -gt 20 ]; then
    echo "  ... and $((string_count - 20)) more"
fi
echo ""

##############################################################################
# Export options
##############################################################################

if [ "$1" = "--export" ]; then
    OUTPUT_FILE="${SCRIPT_DIR}/config-types-report.json"
    
    echo -e "${BLUE}Exporting to JSON: $OUTPUT_FILE${NC}"
    
    jq -n \
        --arg total "$TOTAL" \
        --arg boolean_count "$boolean_count" \
        --arg integer_count "$integer_count" \
        --arg float_count "$float_count" \
        --arg string_count "$string_count" \
        --argjson boolean "$(printf '%s\n' "${boolean_params[@]}" | jq -R . | jq -s .)" \
        --argjson integer "$(printf '%s\n' "${integer_params[@]}" | jq -R . | jq -s .)" \
        --argjson float "$(printf '%s\n' "${float_params[@]}" | jq -R . | jq -s .)" \
        --argjson string "$(printf '%s\n' "${string_params[@]}" | jq -R . | jq -s .)" \
        '{
            total: $total,
            summary: {
                boolean: $boolean_count,
                integer: $integer_count,
                float: $float_count,
                string: $string_count
            },
            parameters: {
                boolean: $boolean,
                integer: $integer,
                float: $float,
                string: $string
            }
        }' > "$OUTPUT_FILE"
    
    echo -e "${GREEN}Report exported successfully!${NC}"
fi

echo -e "${BLUE}==================================================================${NC}"
echo ""
echo "To export this report as JSON, run:"
echo "  $0 --export"
echo ""

