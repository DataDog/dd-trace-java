#!/bin/bash

set -euo pipefail

java -jar "!AGENT_JAR!" uploadCrash "!JAVA_ERROR_FILE!"
if [ $? -eq 0 ]; then
  echo "Uploaded error file \"!JAVA_ERROR_FILE!\""
fi
