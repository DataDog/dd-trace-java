#!/bin/bash

##############################################################################
# Example: Using fuzz-export-vars.sh to export random configurations
# 
# This demonstrates how to export DD configuration variables from another
# script and use them to run your Java application.
##############################################################################

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "======================================================================"
echo "Example: Export Random DD Configurations"
echo "======================================================================"
echo ""

echo "Step 1: Export random configuration variables"
echo "----------------------------------------------------------------------"

# Export variables using eval
eval "$(${SCRIPT_DIR}/fuzz-export-vars.sh)"

echo ""
echo "======================================================================"
echo "Step 2: View exported variables"
echo "======================================================================"
echo ""

# Show all exported DD_ variables
echo "Exported DD_ configuration variables:"
env | grep "^DD_" | sort
echo ""
echo "Total: $(env | grep "^DD_" | wc -l | tr -d " ") DD_ variables exported"
echo ""

echo "======================================================================"
echo "Step 3: Run your Java application"
echo "======================================================================"
echo ""

echo "Now you can run your Java application with these configurations:"
echo ""
echo "  java -javaagent:dd-java-agent.jar -jar myapp.jar"
echo ""
echo "Or any other command that uses DD_ environment variables."
echo ""

echo "======================================================================"
echo "Additional Examples"
echo "======================================================================"
echo ""

echo "1. Export and run immediately:"
echo "   eval \"\$(./fuzz-export-vars.sh)\" && java -jar myapp.jar"
echo ""

echo "2. Export specific number of parameters:"
echo "   FUZZ_MAX_PARAMS=5 eval \"\$(./fuzz-export-vars.sh)\""
echo ""

echo "3. Use in a loop for multiple test runs:"
echo "   for i in {1..10}; do"
echo "     unset \$(env | grep '^DD_' | cut -d'=' -f1)"
echo "     eval \"\$(./fuzz-export-vars.sh)\""
echo "     java -jar myapp.jar"
echo "   done"
echo ""

echo "======================================================================"
echo "Complete!"
echo "======================================================================"

