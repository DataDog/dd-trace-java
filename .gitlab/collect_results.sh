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

# Main project modules redirect their build directory to workspace/<project-path>/build/ in CI
# (see build.gradle.kts layout.buildDirectory override). buildSrc is a separate Gradle build
# that runs before the main build is configured, so this redirect never applies to it;
# its test results always land in buildSrc/**/build/test-results/, not under workspace/.
SEARCH_DIRS=($WORKSPACE_DIR buildSrc)

mapfile -t TEST_RESULT_DIRS < <(find "${SEARCH_DIRS[@]}" -name test-results -type d)

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
    class="${class##*"$"}" # remove inner class name if it exists
    set +e # allow grep to fail
    common_root=$(grep -rl "class $class\|static class $class" "$file_path" 2>/dev/null | head -n 1)
    set -e

    if [[ -n "$common_root" ]]; then
      while IFS= read -r line; do
        while [[ $line != "$common_root"* ]]; do
          common_root=$(dirname "$common_root")
          if [[ "$common_root" == "$common_root/.." ]] || [[ "$common_root" == "/" ]]; then
            common_root=""
            break
          fi
        done
      done < <(grep -rl "class $class\|static class $class" "$file_path" 2>/dev/null)

      if [[ -n "$common_root" && "$common_root" != "/" ]]; then
        file_path="/$common_root"
      else
        file_path="UNKNOWN"
      fi
    else
      file_path="UNKNOWN"
    fi
  fi
}

echo "Saving test results:"

# Collect normalized XML paths so the Java tagger can run once for the whole batch
# instead of paying JVM startup per file.
BATCH_FILE="./synthetic-tag-batch.list"
: > "$BATCH_FILE"

while IFS= read -r -d '' RESULT_XML_FILE
do
  echo -n "- $RESULT_XML_FILE"
  # Assuming the path looks like that: dd-java-agent/instrumentation/tomcat/tomcat-5.5/build/test-results/forkedTest/TEST-TomcatServletV1ForkedTest.xml
  # it will extracts 3 components from the path (counting from the end), to form the new name AGGREGATED_FILE_NAME:
  #
  #  1. Field 1 (from end): The XML filename itself
  #  2. Field 2 (from end): The test suite type (test, forkedTest, etc.)
  #  3. Field 5 (from end): The module/subproject name
  #
  # E.g. for the example path: tomcat-5.5_forkedTest_TEST-TomcatServletV1ForkedTest.xml
  AGGREGATED_FILE_NAME=$(echo "$RESULT_XML_FILE" | rev | cut -d "/" -f 1,2,5 | rev | tr "/" "_")
  echo -n " as $AGGREGATED_FILE_NAME"
  TARGET_DIR="$TEST_RESULTS_DIR"
  mkdir -p "$TARGET_DIR"
  cp "$RESULT_XML_FILE" "$TARGET_DIR/$AGGREGATED_FILE_NAME"
  # Insert file attribute to testcase XML nodes
  get_source_file
  sed -i "/<testcase/ s|\(time=\"[^\"]*\"\)|\1 file=\"$file_path\"|g" "$TARGET_DIR/$AGGREGATED_FILE_NAME"
  # Replace Java Object hashCode by marker in testcase XML nodes to get stable test names
  sed -i '/<testcase/ s/@[0-9a-f]\{5,\}/@HASHCODE/g' "$TARGET_DIR/$AGGREGATED_FILE_NAME"
  # Replace random port numbers by marker in testcase XML nodes to get stable test names
  sed -i '/<testcase/ s/localhost:[0-9]\{2,5\}/localhost:PORT/g' "$TARGET_DIR/$AGGREGATED_FILE_NAME"

  if cmp -s "$RESULT_XML_FILE" "$TARGET_DIR/$AGGREGATED_FILE_NAME"; then
    echo ""
  else
    echo " (non-stable test names detected)"
  fi

  echo "$TARGET_DIR/$AGGREGATED_FILE_NAME" >> "$BATCH_FILE"
done <   <(find "${TEST_RESULT_DIRS[@]}" -name \*.xml -print0)

# Tag every testcase with dd_tags[test.final_status]:
#  - synthetic testcases (intermediate initializationError, executionError, test exception) -> skip
#  - everything else -> pass/skip/fail derived from <failure>/<error>/<skipped> children
if [ -s "$BATCH_FILE" ]; then
  echo "Add dd_tags[test.final_status] property to every testcase (batched, $(wc -l < "$BATCH_FILE") files)"
  $JAVA_25_HOME/bin/java "$(dirname "$0")/TagSyntheticFailures.java" "$BATCH_FILE"
fi
rm -f "$BATCH_FILE"
