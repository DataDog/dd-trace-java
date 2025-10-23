#!/bin/bash
set -e

#
# Handling community pull requests.
#
# The main way to handle community contributions is to contribute directly on the contributor's pull request.
# If not enabled, please ask the contributor to enable the option "Allow edits by maintainers".
# This allows maintainers to push commits to the contributor's branch.
# Then, use this script to mirror the PR to a new branch in the main repository.
# This allows running CI with the maintainer's permissions.
#
# Few hints:
# - Avoid merge commits with master and rebase the contributor's branch instead.
# - Avoid pushing changes to the mirror branch as it will be overwritten when rerunning the script.
#
# Usage: mirror-community-pull-request.sh <pr-number> [<target-branch>]

REPO="DataDog/dd-trace-java"
PR_NUMBER=$1
TARGET_BRANCH=${2:-master}
MIRROR_BRANCH="community-pr-$PR_NUMBER"

#
# Check arguments.
#
# Check if no arguments are provided
if [ $# -eq 0 ]; then
    echo "Usage: $0 <pr-number> [<target-branch>]"
    echo "<pr-number>: PR number to mirror"
    echo "<target-branch>: Target branch for the mirror (default: master)"
    echo ""
    echo "This script mirrors a community PR to allow running CI with your own account."
    echo "It creates a new branch 'community-pr-<number>' and pushes the PR changes."
    exit 1
fi
# Check PR number is provided
if [ -z "$PR_NUMBER" ]; then
    echo "❌ PR number is not provided"
    exit 1
fi
# Validate PR number is numeric
if ! [[ "$PR_NUMBER" =~ ^[0-9]+$ ]]; then
    echo "❌ PR number must be numeric"
    exit 1
fi

#
# Check requirements.
#
echo "- Checking requirements"
# Check gh is installed
gh --version 1>/dev/null 2>&1 || { echo "❌ gh is not installed. Please install GitHub CLI."; exit 1; }
# Check that user is logged into gh cli
gh auth status 1>/dev/null 2>&1 || { echo "❌ Not logged into Github CLI. Please login with \`gh auth status\`."; exit 1; }
# Check jq is installed
jq --version 1>/dev/null 2>&1 || { echo "❌ jq is not installed. Please install jq."; exit 1; }
# Check there are no local changes
git diff --quiet --exit-code || { echo "❌ There are local changes. Please commit or stash them."; exit 1; }

#
# Fetch PR information.
#
echo "- Fetching PR #$PR_NUMBER details"
exit 1
# Check if PR exists and get details
PR_DATA=$(gh pr view "$PR_NUMBER" --json headRepository,headRepositoryOwner,headRefName,title,number,state,author 2>/dev/null || echo "")
if [ -z "$PR_DATA" ]; then
    echo "❌ PR #$PR_NUMBER not found"
    exit 1
fi
# Parse PR details
FORK_REPO=$(echo "$PR_DATA" | jq -r '(.headRepository.nameWithOwner | select(. != "" and . != null)) // (.headRepositoryOwner.login + "/" + .headRepository.name) // empty')
FORK_BRANCH=$(echo "$PR_DATA" | jq -r '.headRefName // empty')
PR_TITLE=$(echo "$PR_DATA" | jq -r '.title // empty')
PR_AUTHOR=$(echo "$PR_DATA" | jq -r '.author.login // empty')
PR_LABELS=$(gh pr view "$PR_NUMBER" --json labels --jq '[.labels[].name] | join(",")')
if [ -z "$FORK_REPO" ] || [ -z "$FORK_BRANCH" ]; then
    echo "❌ Could not determine fork repository or branch for PR #$PR_NUMBER"
    exit 1
fi

#
# Create mirror branch.
#
echo "- Mirroring PR #$PR_NUMBER from $FORK_REPO:$FORK_BRANCH to $REPO:$MIRROR_BRANCH"
# Check if mirror branch already exists
echo "- Checking if mirror branch $MIRROR_BRANCH already exists locally"
if git show-ref --verify --quiet "refs/heads/$MIRROR_BRANCH" 2>/dev/null; then
    echo -n "Branch $MIRROR_BRANCH already exists locally. Delete it to recreate? (y/n)"
    read -r ANSWER
    if [ "$ANSWER" = "y" ]; then
        git branch -D "$MIRROR_BRANCH"
    else
        echo "Aborting."
        exit 1
    fi
fi
# Check if mirror branch exists on remote
echo "- Checking if mirror branch $MIRROR_BRANCH already exists on remote"
if git show-ref --verify --quiet "refs/remotes/origin/$MIRROR_BRANCH" 2>/dev/null; then
    echo -n "Branch $MIRROR_BRANCH already exists on remote. Force push over it? (y/n)"
    read -r ANSWER
    if [ "$ANSWER" != "y" ]; then
        echo "Aborting."
        exit 1
    fi
fi
# Fetch the PR branch from the fork directly (no remote needed)
git fetch --quiet "https://github.com/$FORK_REPO.git" "$FORK_BRANCH"
# Get list of commits from the PR to re-sign them
echo "- Getting list of commits from PR"
PR_COMMITS=$(gh pr view "$PR_NUMBER" --json commits --jq '.commits[].oid')
if [ -z "$PR_COMMITS" ]; then
    echo "❌ No commits found in PR #$PR_NUMBER"
    exit 1
fi
# Get current git branch for cleanup
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
# Create the mirror branch from target branch (we'll cherry-pick commits)
echo "- Creating mirror branch $MIRROR_BRANCH from $TARGET_BRANCH"
git fetch --quiet origin "$TARGET_BRANCH"
git checkout -b "$MIRROR_BRANCH" "origin/$TARGET_BRANCH"

#
# Mirror commits.
#
# Cherry-pick each commit with signature to ensure all commits are signed
echo "- Cherry-picking and signing commits from PR"
for COMMIT in $PR_COMMITS; do
    echo "  - Cherry-picking $COMMIT"
    # Check if this is a merge commit
    CHERRY_PICK_ARGS=("-S")
    PARENT_COUNT=$(git rev-list --parents -n 1 "$COMMIT" 2>/dev/null | wc -w)
    if [ "$PARENT_COUNT" -gt 2 ]; then
        CHERRY_PICK_ARGS+=("-m" "1")
    fi
    if ! git cherry-pick "${CHERRY_PICK_ARGS[@]}" "$COMMIT"; then
        # Check if it's an empty commit
        if ! git diff --cached --quiet || ! git diff --quiet; then
            echo "❌ Failed to cherry-pick merge commit $COMMIT"
            echo "You may need to resolve conflicts manually"
            exit 1
        else
            echo "    (empty commit, skipping)"
            git cherry-pick --skip
        fi
    fi
done
# Push the mirror branch to origin
echo "- Pushing $MIRROR_BRANCH to origin"
git push -u origin "$MIRROR_BRANCH" --no-verify --force-with-lease

#
# Create PR if it doesn't exist.
#
echo "- Checking if PR already exists for branch $MIRROR_BRANCH"
EXISTING_PR=$(gh pr list --head "$MIRROR_BRANCH" --json number --jq '.[0].number // empty' 2>/dev/null)
if [ -n "$EXISTING_PR" ]; then
    MIRROR_PR_URL="https://github.com/$REPO/pull/$EXISTING_PR"
else
    echo "- Creating new PR for mirror branch $MIRROR_BRANCH"
    # Create PR body with reference to original
    MIRROR_PR_BODY="This PR mirrors the changes from the original community contribution to enable CI testing with maintainer privileges.

**Original PR:** https://github.com/$REPO/pull/$PR_NUMBER
**Original Author:** @$PR_AUTHOR
**Original Branch:** $FORK_REPO:$FORK_BRANCH

Closes #$PR_NUMBER

---

*This is an automated mirror created to run CI checks. See tooling/mirror-community-pull-request.sh for details.*"

    # Create the PR
    CREATE_ARGS=(--base "$TARGET_BRANCH" --head "$MIRROR_BRANCH" --title "🪞 $PR_NUMBER - $PR_TITLE" --body "$MIRROR_PR_BODY")
    if [ -n "$PR_LABELS" ]; then
        CREATE_ARGS+=(--label "$PR_LABELS")
    fi
    MIRROR_PR_NUMBER=$(gh pr create "${CREATE_ARGS[@]}" 2>/dev/null | grep -o '[0-9]*$')
    if [ -n "$MIRROR_PR_NUMBER" ]; then
        echo "- Created mirror PR: #$MIRROR_PR_NUMBER"
        MIRROR_PR_URL="https://github.com/$REPO/pull/$MIRROR_PR_NUMBER"
    else
        echo "- Failed to create PR automatically"
        MIRROR_PR_URL="https://github.com/$REPO/compare/$TARGET_BRANCH...$MIRROR_BRANCH"
    fi
fi

echo ""
echo "✅ Successfully mirrored PR #$PR_NUMBER"
echo "   Original: https://github.com/$REPO/pull/$PR_NUMBER (@$PR_AUTHOR)"
echo "   Mirror: $MIRROR_PR_URL"
echo "   Branch: $REPO:$MIRROR_BRANCH"

#
# Clean up.
#
echo "- Restoring original branch"
git checkout "$CURRENT_BRANCH"

echo "Done."
