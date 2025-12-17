#!/bin/bash

##############################################################################
# DD-Trace-Java Configuration Fuzzer - Export Generator
# 
# This script generates export statements for random dd-trace-java
# configuration parameters. Use it with eval to export variables.
#
# Usage: eval "$(./fuzz-export-vars.sh)"
#
# Example:
#   eval "$(./fuzz-export-vars.sh)"
#   java -javaagent:dd-java-agent.jar -jar myapp.jar
##############################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${SCRIPT_DIR}/metadata/supported-configurations.json"
MAX_PARAMS="${FUZZ_MAX_PARAMS:-10}"

# Extract all configuration keys
if [ ! -f "$CONFIG_FILE" ]; then
    echo "# Error: Configuration file not found: $CONFIG_FILE" >&2
    exit 1
fi

CONFIGS=($(jq -r '.supportedConfigurations | keys[]' "$CONFIG_FILE" 2>/dev/null))
TOTAL_CONFIGS=${#CONFIGS[@]}

if [ "$TOTAL_CONFIGS" -eq 0 ]; then
    echo "# Error: No configurations found" >&2
    exit 1
fi

##############################################################################
# Value generation functions (same as fuzz-configs.sh)
##############################################################################

generate_boolean() {
    local values=("true" "false" "1" "0")
    echo "${values[$((RANDOM % ${#values[@]}))]}"
}

generate_integer() {
    local param_name="$1"
    if [[ "$param_name" =~ PORT ]]; then
        echo $((1024 + RANDOM % 64512))
    elif [[ "$param_name" =~ TIMEOUT|DELAY ]]; then
        echo $((100 + RANDOM % 30000))
    elif [[ "$param_name" =~ SIZE|LIMIT|MAX|DEPTH ]]; then
        local max_values=(10 50 100 500 1000 5000 10000)
        echo "${max_values[$((RANDOM % ${#max_values[@]}))]}"
    elif [[ "$param_name" =~ COUNT|NUM ]]; then
        echo $((1 + RANDOM % 100))
    elif [[ "$param_name" =~ RATE|PERCENT ]]; then
        echo $((RANDOM % 101))
    else
        echo $((RANDOM % 1000))
    fi
}

generate_float() {
    local param_name="$1"
    if [[ "$param_name" =~ RATE|SAMPLE ]]; then
        echo "0.$((RANDOM % 100))"
    elif [[ "$param_name" =~ INTERVAL ]]; then
        echo "$((1 + RANDOM % 60)).$((RANDOM % 100))"
    else
        echo "$((RANDOM % 100)).$((RANDOM % 100))"
    fi
}

generate_string() {
    local param_name="$1"
    
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
        local urls=("http://localhost:8080" "https://api.example.com" "http://127.0.0.1:9000")
        echo "${urls[$((RANDOM % ${#urls[@]}))]}"
    elif [[ "$param_name" =~ PATH|FILE|DIR ]]; then
        local paths=("/tmp/test" "/var/log/app" "/opt/datadog" "./config")
        echo "${paths[$((RANDOM % ${#paths[@]}))]}"
    elif [[ "$param_name" =~ KEY|TOKEN ]]; then
        echo "$(head -c 16 /dev/urandom | xxd -p -c 32)"
    elif [[ "$param_name" =~ LEVEL ]]; then
        local levels=("DEBUG" "INFO" "WARN" "ERROR" "TRACE")
        echo "${levels[$((RANDOM % ${#levels[@]}))]}"
    elif [[ "$param_name" =~ MODE ]]; then
        local modes=("full" "service" "disabled" "safe")
        echo "${modes[$((RANDOM % ${#modes[@]}))]}"
    elif [[ "$param_name" =~ TAGS$ ]]; then
        echo "key1:value${RANDOM},key2:value${RANDOM}"
    elif [[ "$param_name" =~ PROPAGATION_STYLE ]]; then
        local styles=("datadog" "b3" "tracecontext" "datadog,b3")
        echo "${styles[$((RANDOM % ${#styles[@]}))]}"
    else
        local generic=("test-value" "example" "config-${RANDOM}" "auto" "default")
        echo "${generic[$((RANDOM % ${#generic[@]}))]}"
    fi
}

generate_value() {
    local param_name="$1"
    
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
# Generate export statements
##############################################################################

# Determine number of parameters
num_params=$((1 + RANDOM % MAX_PARAMS))

# Select random parameters
selected_indices=()
while [ ${#selected_indices[@]} -lt $num_params ]; do
    idx=$((RANDOM % TOTAL_CONFIGS))
    if [[ ! " ${selected_indices[@]} " =~ " ${idx} " ]]; then
        selected_indices+=($idx)
    fi
done

# Output comment header (to stderr so it doesn't affect eval)
echo "# Exporting $num_params random DD configuration parameters..." >&2

# Generate export statements
for idx in "${selected_indices[@]}"; do
    param="${CONFIGS[$idx]}"
    value=$(generate_value "$param")
    # Output the export statement
    echo "export ${param}='${value}'"
    # Log to stderr
    echo "#   ${param}=${value}" >&2
done

echo "# Export complete!" >&2

