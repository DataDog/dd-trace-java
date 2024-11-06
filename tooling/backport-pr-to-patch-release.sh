#!/bin/bash
set -e

PATCH_RELEASE_NAME=$1
PATCH_RELEASE_BRANCH=release/$PATCH_RELEASE_NAME
PR_NUMBER=$2

#
# Check arguments.
#
# Check if no arguments are provided
if [ $# -eq 0 ]; then
    echo "Usage: $0 <patch-release-name> <pr-number>"
    echo "<patch-release-name>: v1.2.x or release/v1.2.x"
    echo "<pr-number>: PR number to backport"
    exit 1
fi
# Check patch release name is provided
if [ -z "$PATCH_RELEASE_NAME" ]; then
    echo "Patch release name is not provided: $0 <patch-release-name> <pr-number>"
    exit 1
fi
# Check patch release name starts with "release/"
if [[ ! "$PATCH_RELEASE_NAME" =~ ^release/.* ]]; then
    PATCH_RELEASE_NAME="release/$PATCH_RELEASE_NAME"
fi
# Check PR number is provided
if [ -z "$PR_NUMBER" ]; then
    echo "PR number is not provided"
    exit 1
fi

#
# Check requirements.
#
# Get current git branch
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
# Check gh is installed
echo "- Checking gh is installed"
gh --version 1>/dev/null 2>&1 || { echo "gh is not installed"; exit 1; }
# Check jq is installed
echo "- Checking jq is installed"
jq --version 1>/dev/null 2>&1 || { echo "jq is not installed"; exit 1; }
# Check there is no local changes
echo "- Checking there is no local changes"
git diff --exit-code || { echo "There are local changes"; exit 1; }
# Check remote branch exists
echo "- Checking remote release branch exists"
git fetch --quiet
git show-ref --verify --quiet "refs/remotes/origin/$PATCH_RELEASE_BRANCH" 1>/dev/null 2>&1 || { echo "Branch $PATCH_RELEASE_BRANCH does not exist"; exit 1; }
# Check PR exists
echo "- Checking PR exists"
PR_COMMITS=$(gh pr view "$PR_NUMBER" --json commits --jq '.commits[].oid')
if [ -z "$PR_COMMITS" ]; then
    echo "PR $PR_NUMBER does not exist"
    exit 1
fi
PR_TITLE=$(gh pr view "$PR_NUMBER" --json title --jq '.title')
PR_LABELS=$(gh pr view "$PR_NUMBER" --json labels --jq '[.labels[].name] | join(",")')

#
# Backport PR to patch release branch.
#
# Checkout release branch
git checkout "$PATCH_RELEASE_BRANCH"
# Ensure the branch is up-to-date
git pull
# Create a new branch for the backport
BRANCH_NAME="$USER/backport-pr-$PR_NUMBER"
git checkout -b "$BRANCH_NAME"
# Cherry-pick PR commits
for PR_COMMIT in $PR_COMMITS; do
    git cherry-pick -x "$PR_COMMIT"
done
# Push the branch
git push -u origin "$BRANCH_NAME" --no-verify
# Create a PR
gh pr create --base "$PATCH_RELEASE_BRANCH" \
    --head "$BRANCH_NAME" \
    --title "[🍒 $PR_NUMBER] $PR_TITLE" \
    --body "Backport #$PR_NUMBER to $PATCH_RELEASE_BRANCH" \
    --label "$PR_LABELS"

#
# Clean up.
#
# Restore current branch
echo "- Restoring original state"
git checkout "$CURRENT_BRANCH"
