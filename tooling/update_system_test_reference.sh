#!/usr/bin/env bash
set -euo pipefail

# This script updates the system-tests references used by GitHub Actions (run-system-tests.yaml) and GitLab CI (.gitlab-ci.yml).
# The reference will be updated with the latest commit SHA of the given branch (or `main` if not set) of https://github.com/DataDog/system-tests.
# Usage: BRANCH=<branch-name> tooling/update_system_test_reference.sh

# Set BRANCH to main if not set
if [ -z "${BRANCH:-}" ]; then
    BRANCH="main"
    echo "BRANCH is not set. Defaulting to 'main'."
fi

GITHUB_TARGET=".github/workflows/run-system-tests.yaml"
GITLAB_TARGET=".gitlab-ci.yml"
GITHUB_PATTERN_1='(\s*system-tests\.yml@)(\S+)(\s+# system tests.*)' # pattern to update the "system-tests.yml@" reference
GITHUB_PATTERN_2='(\s*ref: )(\S+)(\s+# system tests.*)' # pattern to update the "ref:" reference
GITLAB_PATTERN='(\s*SYSTEM_TESTS_REF:\s*)(\S+)(\s+# system tests.*)' # pattern to update the GitLab SYSTEM_TESTS_REF variable

echo "Fetching latest commit SHA for system-tests branch: $BRANCH"
REF=$(git ls-remote https://github.com/DataDog/system-tests "refs/heads/$BRANCH" | cut -f 1)
if [ -z "$REF" ]; then
    echo "Error: Failed to fetch commit SHA for branch $BRANCH"
    exit 1
fi
echo "Fetched SHA: $REF"

if [ ! -f "$GITHUB_TARGET" ]; then
    echo "Error: Target file $GITHUB_TARGET does not exist"
    exit 1
fi

if [ ! -f "$GITLAB_TARGET" ]; then
    echo "Error: Target file $GITLAB_TARGET does not exist"
    exit 1
fi

# Save the substitution results to a temporary file first
TEMP_FILE=$(mktemp)

# Update the "system-tests.yml@" reference
echo "Updating 'system-tests.yml@' reference..."
perl -pe "s/$GITHUB_PATTERN_1/\${1}$REF\${3}/g" "$GITHUB_TARGET" > "$TEMP_FILE"
cp "$TEMP_FILE" "$GITHUB_TARGET"

# Update the "ref:" reference
echo "Updating 'ref:' reference..."
perl -pe "s/$GITHUB_PATTERN_2/\${1}$REF\${3}/g" "$GITHUB_TARGET" > "$TEMP_FILE"
cp "$TEMP_FILE" "$GITHUB_TARGET"

# Update the GitLab SYSTEM_TESTS_REF variable
echo "Updating 'SYSTEM_TESTS_REF' reference..."
perl -pe "s/$GITLAB_PATTERN/\${1}$REF\${3}/g" "$GITLAB_TARGET" > "$TEMP_FILE"
cp "$TEMP_FILE" "$GITLAB_TARGET"

# Clean up temporary file
rm -f "$TEMP_FILE"

echo "Done updating system-tests references to $REF"
