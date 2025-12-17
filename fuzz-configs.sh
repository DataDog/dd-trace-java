#!/bin/bash

##############################################################################
# DD-Trace-Java Configuration Fuzzer
# 
# This script generates random but sensible configuration values for 
# dd-trace-java and runs your application with them for testing.
#
# Usage: ./fuzz-configs.sh <iterations> <java_command_with_args>
#
# Example: ./fuzz-configs.sh 10 "java -jar myapp.jar"
#
# Export-Only Mode:
# Set FUZZ_EXPORT_ONLY=true to only export variables without running command.
# This allows you to source the script from another script and use the vars.
#
# Example: FUZZ_EXPORT_ONLY=true source ./fuzz-configs.sh 1 ""
##############################################################################

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${SCRIPT_DIR}/metadata/supported-configurations.json"
MAX_PARAMS_PER_RUN=10
LOG_DIR="${SCRIPT_DIR}/fuzz-logs"
ITERATIONS="${1:-5}"
JAVA_CMD="${2:-echo 'No Java command specified. Using echo for testing'}"

# Export-only mode: if set to "true", only exports variables without running command
EXPORT_ONLY_MODE="${FUZZ_EXPORT_ONLY:-false}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Create log directory
mkdir -p "$LOG_DIR"

echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  DD-Trace-Java Configuration Fuzzer${NC}"
echo -e "${BLUE}==================================================================${NC}"
echo ""

# Extract all configuration keys from the JSON file
if [ ! -f "$CONFIG_FILE" ]; then
    echo -e "${RED}Error: Configuration file not found: $CONFIG_FILE${NC}"
    exit 1
fi

echo -e "${YELLOW}Extracting configuration parameters...${NC}"
CONFIGS=($(jq -r '.supportedConfigurations | keys[]' "$CONFIG_FILE"))
TOTAL_CONFIGS=${#CONFIGS[@]}
echo -e "${GREEN}Found $TOTAL_CONFIGS configuration parameters${NC}"
echo ""

##############################################################################
# Function to generate a random boolean value
##############################################################################
generate_boolean() {
    local values=("true" "false" "1" "0")
    echo "${values[$((RANDOM % ${#values[@]}))]}"
}

##############################################################################
# Function to generate a random integer
##############################################################################
generate_integer() {
    local param_name="$1"
    
    # Analyze parameter name for hints about range
    if [[ "$param_name" =~ PORT ]]; then
        echo $((1024 + RANDOM % 64512))  # Port range: 1024-65535
    elif [[ "$param_name" =~ TIMEOUT|DELAY ]]; then
        echo $((100 + RANDOM % 30000))   # Timeout: 100-30000ms
    elif [[ "$param_name" =~ SIZE|LIMIT|MAX|DEPTH ]]; then
        local max_values=(10 50 100 500 1000 5000 10000)
        echo "${max_values[$((RANDOM % ${#max_values[@]}))]}"
    elif [[ "$param_name" =~ COUNT|NUM ]]; then
        echo $((1 + RANDOM % 100))       # Count: 1-100
    elif [[ "$param_name" =~ RATE|PERCENT ]]; then
        echo $((RANDOM % 101))           # Rate: 0-100
    else
        echo $((RANDOM % 1000))          # Default: 0-999
    fi
}

##############################################################################
# Function to generate a random float/rate
##############################################################################
generate_float() {
    local param_name="$1"
    
    if [[ "$param_name" =~ RATE|SAMPLE ]]; then
        # Sample rates typically 0.0-1.0
        echo "0.$((RANDOM % 100))"
    elif [[ "$param_name" =~ INTERVAL ]]; then
        # Intervals can be larger
        echo "$((1 + RANDOM % 60)).$((RANDOM % 100))"
    else
        echo "$((RANDOM % 100)).$((RANDOM % 100))"
    fi
}

##############################################################################
# Function to generate a random string value
##############################################################################
generate_string() {
    local param_name="$1"
    
    # Analyze parameter name for appropriate string type
    if [[ "$param_name" =~ ^DD_ENV$ ]]; then
        local envs=("production" "staging" "development" "test" "qa")
        echo "${envs[$((RANDOM % ${#envs[@]}))]}"
        
    elif [[ "$param_name" =~ ^DD_SERVICE$ ]]; then
        local services=("my-service" "web-app" "api-gateway" "microservice-${RANDOM}")
        echo "${services[$((RANDOM % ${#services[@]}))]}"
        
    elif [[ "$param_name" =~ ^DD_VERSION$ ]]; then
        echo "v$((1 + RANDOM % 3)).$((RANDOM % 10)).$((RANDOM % 20))"
        
    elif [[ "$param_name" =~ HOST|HOSTNAME ]]; then
        local hosts=("localhost" "127.0.0.1" "agent.local" "192.168.1.100" "datadog-agent")
        echo "${hosts[$((RANDOM % ${#hosts[@]}))]}"
        
    elif [[ "$param_name" =~ URL|ENDPOINT|URI ]]; then
        local urls=("http://localhost:8080" "https://api.example.com" "http://127.0.0.1:9000" "https://agent.datadoghq.com")
        echo "${urls[$((RANDOM % ${#urls[@]}))]}"
        
    elif [[ "$param_name" =~ PATH|FILE|DIR ]]; then
        local paths=("/tmp/test" "/var/log/app" "/opt/datadog" "./config" "/etc/datadog")
        echo "${paths[$((RANDOM % ${#paths[@]}))]}"
        
    elif [[ "$param_name" =~ KEY|TOKEN ]]; then
        # Generate random hex string
        echo "$(head -c 16 /dev/urandom | xxd -p -c 32)"
        
    elif [[ "$param_name" =~ LEVEL ]]; then
        local levels=("DEBUG" "INFO" "WARN" "ERROR" "TRACE" "OFF")
        echo "${levels[$((RANDOM % ${#levels[@]}))]}"
        
    elif [[ "$param_name" =~ MODE ]]; then
        local modes=("full" "service" "disabled" "safe" "extended")
        echo "${modes[$((RANDOM % ${#modes[@]}))]}"
        
    elif [[ "$param_name" =~ TAGS$ ]]; then
        local tag_count=$((1 + RANDOM % 3))
        local tags=()
        for ((i=0; i<tag_count; i++)); do
            tags+=("key${i}:value${RANDOM}")
        done
        IFS=',' eval 'echo "${tags[*]}"'
        
    elif [[ "$param_name" =~ LIST|INCLUDES|EXCLUDES ]]; then
        # Comma-separated list
        local items=("item1" "item2" "com.example.*" "*.test" "org.springframework.*")
        local selected=()
        local count=$((1 + RANDOM % 3))
        for ((i=0; i<count; i++)); do
            selected+=("${items[$((RANDOM % ${#items[@]}))]}")
        done
        IFS=',' eval 'echo "${selected[*]}"'
        
    elif [[ "$param_name" =~ INTEGRATION|INSTRUMENTATION|ENABLED ]]; then
        # These often have specific values
        local values=("true" "false" "auto" "disabled")
        echo "${values[$((RANDOM % ${#values[@]}))]}"
        
    elif [[ "$param_name" =~ PROPAGATION_STYLE ]]; then
        local styles=("datadog" "b3" "b3multi" "tracecontext" "datadog,b3")
        echo "${styles[$((RANDOM % ${#styles[@]}))]}"
        
    elif [[ "$param_name" =~ SPAN_SAMPLING_RULES|TRACE_SAMPLING_RULES ]]; then
        echo '[{"service":".*","sample_rate":0.5}]'
        
    elif [[ "$param_name" =~ PROFILING_UPLOAD_PERIOD ]]; then
        echo $((10 + RANDOM % 600))
        
    else
        # Generic string
        local generic=("test-value" "example" "config-${RANDOM}" "auto" "default")
        echo "${generic[$((RANDOM % ${#generic[@]}))]}"
    fi
}

##############################################################################
# Function to determine parameter type and generate appropriate value
##############################################################################
generate_value() {
    local param_name="$1"
    
    # Determine type based on parameter name patterns
    if [[ "$param_name" =~ ENABLED$|^DD_TRACE_ENABLED$|DEBUG$|COLLECT|HEADER_COLLECTION|REPORTING|SPLIT_BY ]]; then
        generate_boolean
    elif [[ "$param_name" =~ PORT$|TIMEOUT$|DELAY$|SIZE$|LIMIT$|MAX_|DEPTH$|COUNT$|QUEUE_SIZE$|BUFFER ]]; then
        generate_integer "$param_name"
    elif [[ "$param_name" =~ SAMPLE_RATE$|_RATE$ ]] && [[ ! "$param_name" =~ TRACE_RATE_LIMIT ]]; then
        generate_float "$param_name"
    elif [[ "$param_name" =~ INTERVAL$ ]]; then
        if [[ "$param_name" =~ FLUSH_INTERVAL ]]; then
            generate_float "$param_name"
        else
            generate_integer "$param_name"
        fi
    else
        generate_string "$param_name"
    fi
}

##############################################################################
# Function to run a fuzz iteration
##############################################################################
run_fuzz_iteration() {
    local iteration=$1
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local log_file="${LOG_DIR}/fuzz_run_${iteration}_${timestamp}.log"
    
    echo -e "${BLUE}==================================================================${NC}"
    echo -e "${BLUE}Iteration $iteration of $ITERATIONS${NC}"
    echo -e "${BLUE}==================================================================${NC}"
    
    # Randomly select parameters
    local num_params=$((1 + RANDOM % MAX_PARAMS_PER_RUN))
    local selected_indices=()
    
    # Generate unique random indices
    while [ ${#selected_indices[@]} -lt $num_params ]; do
        local idx=$((RANDOM % TOTAL_CONFIGS))
        if [[ ! " ${selected_indices[@]} " =~ " ${idx} " ]]; then
            selected_indices+=($idx)
        fi
    done
    
    # Generate configuration
    echo -e "${YELLOW}Selected $num_params random parameters:${NC}"
    local config_string=""
    local env_exports=""
    
    # Store parameter names and values for consistent use
    declare -A param_values
    
    for idx in "${selected_indices[@]}"; do
        local param="${CONFIGS[$idx]}"
        local value=$(generate_value "$param")
        param_values["$param"]="$value"
        
        echo -e "  ${GREEN}$param${NC} = ${value}"
        config_string="${config_string}${param}=${value}\n"
        env_exports="${env_exports}export ${param}='${value}'\n"
    done
    
    # Save configuration to log file
    {
        echo "# Fuzz Iteration $iteration"
        echo "# Timestamp: $timestamp"
        echo "# Configuration:"
        echo -e "$config_string"
        echo ""
        echo "# Environment Exports:"
        echo -e "$env_exports"
        echo ""
        echo "# Command: $JAVA_CMD"
        echo "=========================================="
        echo ""
    } > "$log_file"
    
    # Export all selected config variables using the same values
    for param in "${!param_values[@]}"; do
        export "${param}=${param_values[$param]}"
    done
    
    # If in export-only mode, skip running the command
    local exit_code=0
    if [ "$EXPORT_ONLY_MODE" = "true" ]; then
        echo ""
        echo -e "${GREEN}✓ Variables exported successfully (export-only mode)${NC}"
        echo -e "${YELLOW}Note: Variables are exported in the current shell environment${NC}"
    else
        # Set environment variables and run command
        echo ""
        echo -e "${YELLOW}Running application...${NC}"
        
        # Run the command with timeout
        if timeout 30s bash -c "$JAVA_CMD" >> "$log_file" 2>&1; then
            echo -e "${GREEN}✓ Iteration $iteration completed successfully${NC}"
        else
            exit_code=$?
            if [ $exit_code -eq 124 ]; then
                echo -e "${YELLOW}⚠ Iteration $iteration timed out (30s limit)${NC}"
            else
                echo -e "${RED}✗ Iteration $iteration failed with exit code: $exit_code${NC}"
            fi
        fi
        
        # Clean up environment variables after running
        for idx in "${selected_indices[@]}"; do
            local param="${CONFIGS[$idx]}"
            unset "$param"
        done
    fi
    
    echo -e "${BLUE}Log saved to: $log_file${NC}"
    echo ""
    
    return $exit_code
}

##############################################################################
# Main execution
##############################################################################

# Validate iterations parameter
if ! [[ "$ITERATIONS" =~ ^[0-9]+$ ]] || [ "$ITERATIONS" -lt 1 ]; then
    echo -e "${RED}Error: Invalid iterations count: $ITERATIONS${NC}"
    echo "Usage: $0 <iterations> <java_command>"
    exit 1
fi

echo -e "${YELLOW}Starting fuzzer with $ITERATIONS iterations${NC}"
echo -e "${YELLOW}Maximum $MAX_PARAMS_PER_RUN parameters per run${NC}"
if [ "$EXPORT_ONLY_MODE" = "true" ]; then
    echo -e "${YELLOW}Mode: Export-only (variables will be exported, command will not run)${NC}"
else
    echo -e "${YELLOW}Java command: $JAVA_CMD${NC}"
fi
echo ""

# Track statistics
successful_runs=0
failed_runs=0
timeout_runs=0

# Run iterations
for ((i=1; i<=ITERATIONS; i++)); do
    run_fuzz_iteration $i
    exit_code=$?
    
    if [ $exit_code -eq 0 ]; then
        ((successful_runs++))
    elif [ $exit_code -eq 124 ]; then
        ((timeout_runs++))
    else
        ((failed_runs++))
    fi
    
    # Brief pause between iterations
    if [ $i -lt $ITERATIONS ]; then
        sleep 2
    fi
done

# Print summary
echo -e "${BLUE}==================================================================${NC}"
echo -e "${BLUE}  Fuzzing Complete - Summary${NC}"
echo -e "${BLUE}==================================================================${NC}"
echo -e "Total iterations:    ${BLUE}$ITERATIONS${NC}"
echo -e "Successful runs:     ${GREEN}$successful_runs${NC}"
echo -e "Failed runs:         ${RED}$failed_runs${NC}"
echo -e "Timeout runs:        ${YELLOW}$timeout_runs${NC}"
echo -e "Logs directory:      ${BLUE}$LOG_DIR${NC}"
echo ""

if [ $failed_runs -gt 0 ]; then
    echo -e "${RED}⚠ Some runs failed. Check logs for details.${NC}"
    exit 1
else
    echo -e "${GREEN}✓ All runs completed without failures!${NC}"
    exit 0
fi

