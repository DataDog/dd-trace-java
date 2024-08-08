#!/usr/bin/env bash

set +e # Disable exit on error

# Check if PID is provided
if [ -z "$1" ]; then
  echo "Warn: No PID provided. Running in legacy mode."
  java -jar "!AGENT_JAR!" uploadCrash "!JAVA_ERROR_FILE!"
  if [ $? -eq 0 ]; then
    echo "Error file !JAVA_ERROR_FILE! was uploaded successfully"
  else
    echo "Error: Failed to upload error file \"!JAVA_ERROR_FILE!\""
    exit 1
  fi
  exit 0
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
# - hs_err: Path to the hs_err log file
declare -A config
while IFS="=" read -r key value; do
    config["$key"]="$value"
done < "$configFile"

# Debug: Print the loaded values (Optional)
echo "Agent Jar: ${config[agent]}"
echo "Error Log: ${config[hs_err]}"
echo "PID: $PID"

# Execute the Java command with the loaded values
java -jar "${config[agent]}" uploadCrash "${config[hs_err]}"
RC=$?
rm -f ${configFile} # Remove the configuration file

if [ $RC -eq 0 ]; then
    echo "Error file ${config[hs_err]} was uploaded successfully"
else
    echo "Error: Failed to upload error file ${config[hs_err]}"
    exit $RC
fi
