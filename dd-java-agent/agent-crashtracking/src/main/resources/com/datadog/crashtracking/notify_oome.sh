#!/usr/bin/env bash

set +e # Disable exit on error

# Check if PID is provided
if [ -z "$1" ]; then
    echo "Error: No PID provided"
    exit 1
fi
HERE="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )" # Get the directory of the script
PID=$1

# Get the base name of the script
scriptName=$(basename "$0" .sh)
configFile="${HERE}/${scriptName}_pid${PID}.cfg"
if [ ! -f "$configFile" ]; then
    echo "Error: Configuration file not found: $configFile"
    exit 1
fi

# Read the configuration file
# The expected contents are:
# - agent: Path to the agent jar
# - tags: Comma-separated list of tags to be sent with the OOME event; key:value pairs are supported
declare -A config
while IFS="=" read -r key value; do
    config["$key"]="$value"
done < "$configFile"

# Debug: Print the loaded values (Optional)
echo "Agent Jar: ${config[agent]}"
echo "Tags: ${config[tags]}"
echo "PID: $PID"

# Execute the Java command with the loaded values
java -Ddd.dogstatsd.start-delay=0 -jar "${config[agent]}" sendOomeEvent "${config[tags]}"
RC=$?
rm -f ${configFile} # Remove the configuration file

if [ $RC -eq 0 ]; then
    echo "OOME Event generated successfully"
else
    echo "Error: Failed to generate OOME event"
    exit $RC
fi
