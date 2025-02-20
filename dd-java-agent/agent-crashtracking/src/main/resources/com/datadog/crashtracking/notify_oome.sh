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
while IFS="=" read -r key value; do
    declare "config_$key"="$value"
done < "$configFile"

# Exiting early if configuration is missing
if [ -z "${config_agent}" ] || [ -z "${config_tags}" ] || [ -z "${config_java_home}" ]; then
    echo "Error: Missing configuration"
    exit 1
fi

# Debug: Print the loaded values (Optional)
echo "Agent Jar: ${config_agent}"
echo "Tags: ${config_tags}"
echo "JAVA_HOME: ${config_java_home}"
echo "PID: $PID"

# Execute the Java command with the loaded values
"${config_java_home}/bin/java" -Ddd.dogstatsd.start-delay=0 -jar "${config_agent}" sendOomeEvent "${config_tags}"
RC=$?
rm -f "${configFile}" # Remove the configuration file

if [ $RC -eq 0 ]; then
    echo "OOME Event generated successfully"
else
    echo "Error: Failed to generate OOME event"
    exit $RC
fi
