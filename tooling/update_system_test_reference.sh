#!/usr/bin/env bash
set -euo pipefail

# This script updates the system-tests reference in run-system-tests.yaml.
# The reference will be updated with the latest commit SHA of the given branch (or `main` if not set) of https://github.com/DataDog/system-tests.
# Usage: BRANCH=<branch-name> tooling/update_system_test_reference.sh

# Set BRANCH to main if not set
if [ -z "${BRANCH:-}" ]; then
    BRANCH="main"
    echo "BRANCH is not set. Defaulting to 'main'."
fi

TARGET=".github/workflows/run-system-tests.yaml" # target file to update
PATTERN_1='(\s*system-tests\.yml@)(\S+)(\s+# system tests.*)' # pattern to update the "system-tests.yml@" reference
PATTERN_2='(\s*ref: )(\S+)(\s+# system tests.*)' # pattern to update the "ref:" reference

echo "Fetching latest commit SHA for system-tests branch: $BRANCH"
REF=$(git ls-remote https://github.com/DataDog/system-tests "refs/heads/$BRANCH" | cut -f 1)
if [ -z "$REF" ]; then
    echo "Error: Failed to fetch commit SHA for branch $BRANCH"
    exit 1
fi
echo "Fetched SHA: $REF"

if [ ! -f "$TARGET" ]; then
    echo "Error: Target file $TARGET does not exist"
    exit 1
fi

# Save the substitution results to a temporary file first
TEMP_FILE=$(mktemp)

# Update the "system-tests.yml@" reference
echo "Updating 'system-tests.yml@' reference..."
perl -pe "s/$PATTERN_1/\${1}$REF\${3}/g" "$TARGET" > "$TEMP_FILE"
cp "$TEMP_FILE" "$TARGET"

# Update the "ref:" reference
echo "Updating 'ref:' reference..."
perl -pe "s/$PATTERN_2/\${1}$REF\${3}/g" "$TARGET" > "$TEMP_FILE"
cp "$TEMP_FILE" "$TARGET"

# Clean up temporary file
rm -f "$TEMP_FILE"

echo "Done updating system-tests references to $REF"
