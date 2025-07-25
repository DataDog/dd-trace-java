#!/usr/bin/env bash

# Save all important reports and artifacts into (project-root)/results
# This folder will be saved by gitlab and available after test runs.

set -e
# Enable '**' support
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

function get_source_file () {
  file_path="${RESULT_XML_FILE%%"/build"*}"
  file_path="${file_path/#"$WORKSPACE_DIR"\//}/src"
  if ! [[ $RESULT_XML_FILE == *"#"* ]]; then
    class="${RESULT_XML_FILE%.xml}"
    class="${class##*"TEST-"}"
    class="${class##*"."}"
    common_root=$(grep -rl "class $class" "$file_path" | head -n 1)
    while IFS= read -r line; do
      while [[ $line != "$common_root"* ]]; do
        common_root=$(dirname "$common_root")
        if [[ "$common_root" == "$common_root/.." ]]; then
          break
        fi
      done
    done < <(grep -rl "class $class" "$file_path")
    file_path="/$common_root"
  fi
}

echo "Saving test results:"
while IFS= read -r -d '' RESULT_XML_FILE
do
  echo -n "- $RESULT_XML_FILE"
  AGGREGATED_FILE_NAME=$(echo "$RESULT_XML_FILE" | rev | cut -d "/" -f 1,2,5 | rev | tr "/" "_")
  echo -n " as $AGGREGATED_FILE_NAME"
  cp "$RESULT_XML_FILE" "$TEST_RESULTS_DIR/$AGGREGATED_FILE_NAME"
  # Insert file attribute to testcase XML nodes
  get_source_file
  sed -i "/<testcase/ s|\(time=\"[^\"]*\"\)|\1 file=\"$file_path\"|g" "$TEST_RESULTS_DIR/$AGGREGATED_FILE_NAME"
  # Replace Java Object hashCode by marker in testcase XML nodes to get stable test names
  sed -i '/<testcase/ s/@[0-9a-f]\{5,\}/@HASHCODE/g' "$TEST_RESULTS_DIR/$AGGREGATED_FILE_NAME"
  # Replace random port numbers by marker in testcase XML nodes to get stable test names
  sed -i '/<testcase/ s/localhost:[0-9]\{2,5\}/localhost:PORT/g' "$TEST_RESULTS_DIR/$AGGREGATED_FILE_NAME"
  if cmp -s "$RESULT_XML_FILE" "$TEST_RESULTS_DIR/$AGGREGATED_FILE_NAME"; then
    echo ""
  else
    echo -n " (non-stable test names detected)"
  fi
done <   <(find "${TEST_RESULT_DIRS[@]}" -name \*.xml -print0)
