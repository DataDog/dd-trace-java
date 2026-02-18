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
config_javacore_path=""

# Read the configuration file
while IFS="=" read -r key value; do
  case "$key" in
    agent) config_agent=$value ;;
    hs_err) config_hs_err=$value ;;
    java_home) config_java_home=$value ;;
    javacore_path) config_javacore_path=$value ;;
  esac
done < "$configFile"

# Exiting early if agent or java_home is missing
if [ -z "$config_agent" ] || [ -z "$config_java_home" ]; then
  echo "Error: Missing configuration (agent or java_home)"
  exit 1
fi

# Function to find J9 javacore file for given PID in a directory
find_javacore() {
  search_dir="$1"
  search_pid="$2"
  # J9 javacore files are named: javacore.<date>.<time>.<pid>.<seq>.txt
  # Find the most recent one for this PID - use strict pattern with dots around PID
  found=$(ls -t "${search_dir}"/javacore.*."${search_pid}".*.txt 2>/dev/null | head -1)
  echo "$found"
}

# Find crash file - support both HotSpot (hs_err) and J9 (javacore) formats
crash_file=""
if [ -n "$config_hs_err" ] && [ -f "$config_hs_err" ]; then
  # HotSpot: use configured error file path
  crash_file="$config_hs_err"
elif [ -f "hs_err_pid${PID}.log" ]; then
  # HotSpot: default error file in current directory
  crash_file="hs_err_pid${PID}.log"
else
  # J9/OpenJ9: look for javacore file matching the PID
  if [ -n "$config_javacore_path" ]; then
    # Custom javacore path configured - check if it's a directory or file pattern
    if [ -d "$config_javacore_path" ]; then
      # It's a directory - search for javacore files there
      crash_file=$(find_javacore "$config_javacore_path" "$PID")
    elif [ -f "$config_javacore_path" ]; then
      # It's an existing file - use it directly
      crash_file="$config_javacore_path"
    else
      # Might be a pattern with %pid - substitute and check
      substituted_path=$(printf '%s' "$config_javacore_path" | sed "s/%pid/$PID/g")
      if [ -f "$substituted_path" ]; then
        crash_file="$substituted_path"
      else
        # Try the directory containing the pattern
        pattern_dir=$(dirname "$config_javacore_path")
        if [ -d "$pattern_dir" ]; then
          crash_file=$(find_javacore "$pattern_dir" "$PID")
        fi
      fi
    fi
  fi

  # Fallback: search in current directory if no custom path or file not found
  if [ -z "$crash_file" ]; then
    crash_file=$(find_javacore "." "$PID")
  fi
fi

if [ -z "$crash_file" ] || [ ! -f "$crash_file" ]; then
  echo "Error: No crash file found for PID $PID"
  if [ -n "$config_javacore_path" ]; then
    echo "  Searched custom path: $config_javacore_path"
  fi
  exit 1
fi

# Use the found crash file
config_hs_err="$crash_file"

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
