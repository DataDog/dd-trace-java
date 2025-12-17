#!/bin/bash

##############################################################################
# Example script showing how to use the dd-trace-java fuzzer
# This creates a minimal test application for demonstration
##############################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "DD-Trace-Java Fuzzer - Quick Start Example"
echo "=========================================="
echo ""

# Check if fuzz-configs.sh exists
if [ ! -f "${SCRIPT_DIR}/fuzz-configs.sh" ]; then
    echo "Error: fuzz-configs.sh not found in ${SCRIPT_DIR}"
    exit 1
fi

# Check if jq is installed
if ! command -v jq &> /dev/null; then
    echo "Error: jq is not installed. Please install it first:"
    echo "  macOS:        brew install jq"
    echo "  Ubuntu/Debian: sudo apt-get install jq"
    echo "  CentOS/RHEL:   sudo yum install jq"
    exit 1
fi

echo "Running fuzzer with 5 test iterations..."
echo ""
echo "This will generate random dd-trace-java configurations and"
echo "run a simple echo command to demonstrate the fuzzer."
echo ""
echo "For real testing, replace the command with your Java application:"
echo "  ./fuzz-configs.sh 10 'java -javaagent:dd-java-agent.jar -jar myapp.jar'"
echo ""
echo "Starting in 3 seconds..."
sleep 3

# Run the fuzzer with a simple echo command for demonstration
"${SCRIPT_DIR}/fuzz-configs.sh" 5 "echo 'Test run completed with above configuration'"

echo ""
echo "=========================================="
echo "Example completed!"
echo ""
echo "Check the fuzz-logs/ directory for detailed logs of each run."
echo ""
echo "Next steps:"
echo "1. Review FUZZ_README.md for detailed documentation"
echo "2. Run with your actual Java application"
echo "3. Analyze the logs to identify any configuration issues"
echo ""

