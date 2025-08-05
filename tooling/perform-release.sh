#!/usr/bin/env bash
set -euo pipefail

# Ask for confirmation before continuing the release
function confirmOrAbort() {
    read -p "Do you want to continue? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborting."
        exit 1
    fi
}

# Check if current branch is either 'master' or 'release/v*'
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$CURRENT_BRANCH" == "master" ]]; then
    MINOR_RELEASE=true
elif [[ "$CURRENT_BRANCH" =~ ^release/v[0-9]+\.[0-9]+\.x$ ]]; then
    MINOR_RELEASE=false
else
    echo "❌ Please check out either 'master' branch or a 'release/v*' branch to perform a release first."
    exit 1
fi
echo -n "✅ Current branch is '$CURRENT_BRANCH'. Performing a "
if [ "$MINOR_RELEASE" = true ]; then
    echo "minor release."
else
    echo "patch release."
fi

# Check upstream branch is set
if ! git rev-parse --abbrev-ref --symbolic-full-name "@{u}" >/dev/null 2>&1; then
    echo "❌ No upstream branch set. Please set the upstream branch for the current branch."
    exit 1
fi

# Get the remote name
REMOTE=$(git config --get "branch.$CURRENT_BRANCH.remote")
if [ -z "$REMOTE" ]; then
    echo "❌ Unable to determine the remote name. Please ensure you have a remote set."
    exit 1
fi

# Check if working copy is clean
if ! git diff-index --quiet HEAD; then
    echo "❌ Working copy is not clean. Please commit or stash your changes before performing a release."
    exit 1
fi

# Check if clone is up to date
if ! git fetch "$REMOTE" --quiet ; then
    echo "❌ Unable to fetch the latest changes from $REMOTE. Please check your network connection."
    exit 1
fi
if ! git diff-index --quiet "$REMOTE/$CURRENT_BRANCH"; then
    echo "❌ Working copy is not up to date with $REMOTE/$CURRENT_BRANCH. Please pull the latest changes before performing a release."
    exit 1
fi
echo "✅ Working copy is clean and up-to-date."

# Check the git log history
LAST_RELEASE_TAG=$(git describe --tags --abbrev=0 --match='v[0-9]*.[0-9]*.[0-9]*')
echo "ℹ️ Last release version: $LAST_RELEASE_TAG"
SUSPICIOUS_COMMITS=$(git log --oneline --first-parent "$LAST_RELEASE_TAG"..HEAD | grep -E -v "Merge pull request #" | grep -E -v "\(#")
if [ -n "$SUSPICIOUS_COMMITS" ]; then
    echo "❌ The following commits are not merge commits and may not be suitable for a release:"
    echo "$SUSPICIOUS_COMMITS"
    echo "Please review these commits before proceeding with the release."
    confirmOrAbort
else
    echo "✅ All commits since the last release are merge commits."
fi

# Get the next release version
VERSION=$(echo "$LAST_RELEASE_TAG" | grep -E '^v[0-9]+\.[0-9]+\.0$' | sed 's/^v//')
if [ -z "$VERSION" ]; then
    echo "❌ Unable to determine the next release version from the last release tag: $LAST_RELEASE_TAG"
    exit 1
fi
if [ "$MINOR_RELEASE" = true ]; then
    NEXT_RELEASE_VERSION=$(echo "$VERSION" | awk -F. '{printf "v%d.%d.0", $1, $2 + 1}')
else
    NEXT_RELEASE_VERSION=$(echo "$VERSION" | awk -F. '{printf "v%d.%d.%d", $1, $2, $3 + 1}')
fi
echo "ℹ️ Next release version: $NEXT_RELEASE_VERSION"

# Create and push the release tag
echo "ℹ️ The release tag will be created and pushed. No abort is possible after this point."
confirmOrAbort
git tag -a -s -m "Release $NEXT_RELEASE_VERSION" "$NEXT_RELEASE_VERSION"
git push "$REMOTE" "$NEXT_RELEASE_VERSION" --no-verify
echo "✅ Release tag $NEXT_RELEASE_VERSION created and pushed to $REMOTE."
