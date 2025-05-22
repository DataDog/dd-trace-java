#!/bin/sh

# Disable exit on error
set +e

# Check if PID is provided
if [ -z "$1" ]; then
    echo "Error: No PID provided"
    exit 1
fi

# Get the directory of the script
HERE=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)
PID=$1

# Get the base name of the script (without .sh)
scriptName=$(basename "$0")
scriptName=${scriptName%.sh}

configFile="${HERE}/${scriptName}_pid${PID}.cfg"
if [ ! -f "$configFile" ]; then
    echo "Error: Configuration file not found: $configFile"
    exit 1
fi

# Initialize config values
config_agent=""
config_tags=""
config_java_home=""

# Read the configuration file
while IFS="=" read -r key value; do
    case "$key" in
        agent) config_agent=$value ;;
        tags) config_tags=$value ;;
        java_home) config_java_home=$value ;;
    esac
done < "$configFile"

# Exiting early if configuration is missing
if [ -z "$config_agent" ] || [ -z "$config_tags" ] || [ -z "$config_java_home" ]; then
    echo "Error: Missing configuration"
    exit 1
fi

# Debug: Print the loaded values (Optional)
echo "Agent Jar: $config_agent"
echo "Tags: $config_tags"
echo "JAVA_HOME: $config_java_home"
echo "PID: $PID"

# Execute the Java command with the loaded values
"$config_java_home/bin/java" -Ddd.dogstatsd.start-delay=0 -jar "$config_agent" sendOomeEvent "$config_tags"
RC=$?

# Remove the configuration file
rm -f "$configFile"

if [ "$RC" -eq 0 ]; then
    echo "OOME Event generated successfully"
else
    echo "Error: Failed to generate OOME event"
    exit "$RC"
fi
