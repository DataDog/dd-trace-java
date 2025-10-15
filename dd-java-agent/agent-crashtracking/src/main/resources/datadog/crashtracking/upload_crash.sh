#!/bin/sh

# Disable exit on error
set +e

# Check if PID is provided
if [ -z "$1" ]; then
  echo "Warn: No PID provided. Running in legacy mode."

  "!JAVA_HOME!/bin/java" -jar "!AGENT_JAR!" uploadCrash "!JAVA_ERROR_FILE!"
  if [ $? -eq 0 ]; then
    echo "Error file !JAVA_ERROR_FILE! was uploaded successfully"
  else
    echo "Error: Failed to upload error file \"!JAVA_ERROR_FILE!\""
    exit 1
  fi
  exit 0
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
config_hs_err=""
config_java_home=""

# Read the configuration file
while IFS="=" read -r key value; do
  case "$key" in
    agent) config_agent=$value ;;
    hs_err) config_hs_err=$value ;;
    java_home) config_java_home=$value ;;
  esac
done < "$configFile"

# Exiting early if configuration is missing
if [ -z "$config_agent" ] || [ -z "$config_hs_err" ] || [ -z "$config_java_home" ]; then
  echo "Error: Missing configuration"
  exit 1
fi

# Debug: Print the loaded values (Optional)
echo "Agent Jar: $config_agent"
echo "Error Log: $config_hs_err"
echo "JAVA_HOME: $config_java_home"
echo "PID: $PID"

# Execute the Java command with the loaded values
"$config_java_home/bin/java" -jar "$config_agent" uploadCrash -c "$configFile" "$config_hs_err"
RC=$?

# Remove the configuration file
rm -f "$configFile"

if [ "$RC" -eq 0 ]; then
  echo "Error file $config_hs_err was uploaded successfully"
else
  echo "Error: Failed to upload error file $config_hs_err"
  exit "$RC"
fi
