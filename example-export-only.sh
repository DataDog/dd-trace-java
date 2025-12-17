#!/bin/bash

##############################################################################
# Example: Using fuzz-configs.sh in export-only mode
# 
# This script demonstrates how to call fuzz-configs.sh from another script
# and use the exported environment variables.
##############################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "======================================================================"
echo "Example: Using Fuzz Configs in Export-Only Mode"
echo "======================================================================"
echo ""

# Enable export-only mode
export FUZZ_EXPORT_ONLY=true

# Run the fuzzer once to export variables (no command execution)
echo "Step 1: Exporting random configuration variables..."
echo "----------------------------------------------------------------------"
source "${SCRIPT_DIR}/fuzz-configs.sh" 1 ""

echo ""
echo "======================================================================"
echo "Step 2: Using the exported variables"
echo "======================================================================"
echo ""

# Now you can use the exported variables
echo "Example 1: List all DD_ variables that were exported:"
env | grep "^DD_" | head -20
echo ""

echo "Example 2: Run your command with the exported variables:"
echo "java -javaagent:dd-java-agent.jar -jar myapp.jar"
echo ""

echo "Example 3: Or run multiple commands with the same configuration:"
echo "----------------------------------------------------------------------"
echo "Command 1: Check configuration"
env | grep "^DD_" | wc -l
echo "Total DD_ variables exported: $(env | grep "^DD_" | wc -l)"

echo ""
echo "Command 2: You can now run your Java application"
echo "(Skipping actual execution for this demo)"
# java -javaagent:dd-java-agent.jar -jar myapp.jar

echo ""
echo "======================================================================"
echo "Complete!"
echo "======================================================================"
echo ""
echo "The exported variables remain in your shell environment until you"
echo "unset them or the shell session ends."
echo ""
echo "To use this pattern in your own script:"
echo "  1. Set: export FUZZ_EXPORT_ONLY=true"
echo "  2. Source: source ./fuzz-configs.sh 1 \"\""
echo "  3. Use the DD_* environment variables as needed"
echo "  4. Run your Java application with the exported configs"
echo ""

