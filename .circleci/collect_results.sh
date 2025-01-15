#!/usr/bin/env bash

# Save all important reports and artifacts into (project-root)/results
# This folder will be saved by circleci and available after test runs.

set -e
#Enable '**' support
shopt -s globstar

TEST_RESULTS_DIR=results
WORKSPACE_DIR=workspace
mkdir -p $TEST_RESULTS_DIR
mkdir -p $WORKSPACE_DIR

mapfile -t TEST_RESULT_DIRS < <(find $WORKSPACE_DIR -name test-results -type d)

if [[ ${#TEST_RESULT_DIRS[@]} -eq 0 ]]; then
  echo "No test results found"
  exit 0
fi

# Read sourceFile.xml into map
declare -A SOURCE_FILE_MAP
SOURCE_FILE_XML="test-results/sourceFiles.xml"
while IFS= read -r line
do
  KEY=$(echo "$line" | cut -d ":" -f 1)
  VALUE=$(echo "$line" | cut -d ":" -f 2)
  SOURCE_FILE_MAP["$KEY"]="$VALUE"
done < "$SOURCE_FILE_XML"

echo "Saving test results:"
while IFS= read -r -d '' RESULT_XML_FILE
do
  echo -n "- $RESULT_XML_FILE"
  AGGREGATED_FILE_NAME=$(echo "$RESULT_XML_FILE" | rev | cut -d "/" -f 1,2,5 | rev | tr "/" "_")
  echo -n " as $AGGREGATED_FILE_NAME"
  cp "$RESULT_XML_FILE" "$TEST_RESULTS_DIR/$AGGREGATED_FILE_NAME"
  # Replace Java Object hashCode by marker in testcase XML nodes to get stable test names
  sed -i '/<testcase/ s/@[0-9a-f]\{5,\}/@HASHCODE/g' "$TEST_RESULTS_DIR/$AGGREGATED_FILE_NAME"
  # Replace random port numbers by marker in testcase XML nodes to get stable test names
  sed -i '/<testcase/ s/localhost:[0-9]\{2,5\}/localhost:PORT/g' "$TEST_RESULTS_DIR/$AGGREGATED_FILE_NAME"

  # Insert file attribute to testcases
  for CLASSNAME in "${!SOURCE_FILE_MAP[@]}"; do
    SOURCE_FILE="${SOURCE_FILE_MAP[CLASSNAME]}"
    sed -i "/<testcase.*classname=$CLASSNAME/ s/\(time=\"[^\"]*\"\)/file=$SOURCE_FILE \1/g" "$TEST_RESULTS_DIR/$AGGREGATED_FILE_NAME"
  done

  if cmp -s "$RESULT_XML_FILE" "$TEST_RESULTS_DIR/$AGGREGATED_FILE_NAME"; then
    echo ""
  else
    echo -n " (non-stable test names detected)"
  fi
done <   <(find "${TEST_RESULT_DIRS[@]}" -name \*.xml -print0)
