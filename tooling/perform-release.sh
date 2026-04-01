#!/usr/bin/env bash
set -euo pipefail

# Ask for confirmation before continuing the release
function confirmOrAbort() {
    local prompt="${1:-Do you want to continue? (y/N): }"
    local reply
    read -p "$prompt" -n 1 -r reply
    echo
    if [[ ! $reply =~ ^[Yy]$ ]]; then
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

# Check if gh CLI is available
if ! command -v gh &>/dev/null; then
    echo "❌ GitHub CLI (gh) is not installed or not in PATH. Please install it to proceed."
    exit 1
fi

# Check gh authentication
if ! gh auth status &>/dev/null; then
    echo "❌ GitHub CLI is not authenticated. Please run 'gh auth login' first."
    exit 1
fi

# Check push permission on this repository
if ! HAS_PUSH=$(gh api "repos/{owner}/{repo}" --jq ".permissions.push"); then
    echo "❌ Failed to query repository permissions. Check your network connection and token scopes."
    exit 1
fi
if [ "$HAS_PUSH" != "true" ]; then
    echo "❌ Your GitHub account does not have push (write) permission on this repository."
    echo "   Only release owners are allowed to push release tags."
    exit 1
fi
echo "✅ GitHub CLI authenticated with push permission."

# Check the git log history
LAST_RELEASE_TAG=$(git describe --tags --abbrev=0 --match='v[0-9]*.[0-9]*.[0-9]*')
echo "ℹ️ Last release version: $LAST_RELEASE_TAG"
SUSPICIOUS_COMMITS=$(git log --oneline --first-parent "$LAST_RELEASE_TAG"..HEAD | grep -E -v "Merge pull request #" | grep -E -v "\(#" || true)
if [ -n "$SUSPICIOUS_COMMITS" ]; then
    echo "❌ The following commits are not merge commits and may not be suitable for a release:"
    echo "$SUSPICIOUS_COMMITS"
    echo "Please review these commits before proceeding with the release."
    confirmOrAbort
else
    echo "✅ All commits since the last release are merge commits."
fi

# Get the next release version
VERSION=$(echo "$LAST_RELEASE_TAG" | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sed 's/^v//' || true)
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

# Check the release tag does not already exist locally or remotely
if git rev-parse "refs/tags/$NEXT_RELEASE_VERSION" &>/dev/null; then
    echo "❌ Tag '$NEXT_RELEASE_VERSION' already exists locally. Has this release already been tagged?"
    exit 1
fi
if git ls-remote --tags "$REMOTE" "refs/tags/$NEXT_RELEASE_VERSION" | grep -q .; then
    echo "❌ Tag '$NEXT_RELEASE_VERSION' already exists on remote '$REMOTE'. Has this release already been tagged?"
    exit 1
fi
echo "✅ Release tag '$NEXT_RELEASE_VERSION' does not yet exist."

# Check that an open GitHub milestone exists for the next release version
MILESTONE_TITLE="${NEXT_RELEASE_VERSION#v}"
if ! MILESTONE_NUMBERS=$(gh api --paginate "repos/{owner}/{repo}/milestones?state=open&per_page=100" \
    --jq ".[] | select(.title == \"$MILESTONE_TITLE\") | .number"); then
    echo "❌ Failed to query GitHub milestones. Check your network connection."
    exit 1
fi
if [ -z "$MILESTONE_NUMBERS" ]; then
    echo "❌ No open GitHub milestone found for version '$MILESTONE_TITLE'."
    echo "   Please create the milestone and assign PRs to it before performing a release."
    exit 1
fi
MILESTONE_COUNT=$(printf '%s\n' "$MILESTONE_NUMBERS" | wc -l | tr -d ' ')
if [ "$MILESTONE_COUNT" -gt 1 ]; then
    echo "❌ Multiple open milestones found for version '$MILESTONE_TITLE' (numbers: $(printf '%s ' "$MILESTONE_NUMBERS"))."
    echo "   Please resolve the duplicate milestones before performing a release."
    exit 1
fi
MILESTONE_NUMBER="$MILESTONE_NUMBERS"
echo "✅ GitHub milestone '$MILESTONE_TITLE' found (milestone #$MILESTONE_NUMBER)."

# Check that the milestone has no open issues or PRs
if ! OPEN_ISSUES=$(gh api "repos/{owner}/{repo}/milestones/$MILESTONE_NUMBER" \
    --jq ".open_issues"); then
    echo "❌ Failed to query milestone '$MILESTONE_TITLE'. Check your network connection."
    exit 1
fi
if [ -z "$OPEN_ISSUES" ] || ! [[ "$OPEN_ISSUES" =~ ^[0-9]+$ ]]; then
    echo "❌ Unexpected response when querying open issue count for milestone '$MILESTONE_TITLE': '$OPEN_ISSUES'."
    exit 1
fi
if [ "$OPEN_ISSUES" -gt 0 ]; then
    echo "❌ Milestone '$MILESTONE_TITLE' still has $OPEN_ISSUES open issue(s) or PR(s):"
    gh api "repos/{owner}/{repo}/issues?milestone=$MILESTONE_NUMBER&state=open&per_page=100" \
        --jq '.[] | "   #\(.number) \(.title)"' || true
    echo "   All PRs must be merged before tagging a release."
    exit 1
fi
echo "✅ All issues and PRs in milestone '$MILESTONE_TITLE' are closed."

# Check that all closed PRs in the milestone carry the required labels.
# Required: (comp:* or inst:*) AND (type:*), OR 'tag: no release note'.
if ! NONCOMPLIANT=$(gh api --paginate \
    "repos/{owner}/{repo}/issues?milestone=$MILESTONE_NUMBER&state=closed&per_page=100" \
    --jq '[.[] | select(.pull_request != null) |
          select(
            ((.labels | map(.name) | map(. == "tag: no release note") | any) | not) and
            (
              ((.labels | map(.name) | map(startswith("comp:") or startswith("inst:")) | any) | not) or
              ((.labels | map(.name) | map(startswith("type:")) | any) | not)
            )
          ) | "   #\(.number) \(.title)"] | .[]'); then
    echo "❌ Failed to query milestone PRs. Check your network connection."
    exit 1
fi
if [ -n "$NONCOMPLIANT" ]; then
    echo "⚠️  The following PRs in milestone '$MILESTONE_TITLE' are missing required labels:"
    echo "$NONCOMPLIANT"
    echo "   Each PR needs (a 'comp:' or 'inst:' label) AND (a 'type:' label), or 'tag: no release note'."
    confirmOrAbort "Continue despite missing labels? (y/N): "
else
    echo "✅ All PRs in milestone '$MILESTONE_TITLE' have required labels."
fi

# Check GPG signing key is configured and available (release tags are signed with -s)
SIGNING_KEY=$(git config --get user.signingkey 2>/dev/null || true)
if [ -n "$SIGNING_KEY" ]; then
    if ! gpg --list-secret-keys "$SIGNING_KEY" &>/dev/null; then
        echo "❌ GPG signing key '$SIGNING_KEY' is not available in the keyring (expired, revoked, or deleted)."
        echo "   Please reconfigure your signing key with: git config user.signingkey <KEY_ID>"
        exit 1
    fi
else
    GIT_EMAIL=$(git config --get user.email 2>/dev/null || true)
    if [ -z "$GIT_EMAIL" ] || ! gpg --list-secret-keys "$GIT_EMAIL" &>/dev/null; then
        echo "❌ No GPG signing key configured. Release tags must be signed."
        echo "   Configure one with: git config user.signingkey <KEY_ID>"
        exit 1
    fi
fi
echo "✅ GPG signing key is configured."

# For minor releases: require explicit acknowledgment of manual pre-cut verification steps
if [ "$MINOR_RELEASE" = true ]; then
    echo ""
    echo "ℹ️ Minor release — manual pre-cut verification required (Steps 1–2 of the release process)."
    confirmOrAbort "Step 1: Have you reviewed the APM Performance SDK SLO dashboard and found no regressions? (y/N): "
    confirmOrAbort "Step 2: Have you reviewed the Test Optimization Performance Dashboard and found no increased overhead? (y/N): "
    echo ""
fi

# Create and push the release tag
echo "ℹ️ The release tag will be created and pushed. No abort is possible after this point."
confirmOrAbort
git tag -a -s -m "Release $NEXT_RELEASE_VERSION" "$NEXT_RELEASE_VERSION"
git push "$REMOTE" "$NEXT_RELEASE_VERSION" --no-verify
echo "✅ Release tag $NEXT_RELEASE_VERSION created and pushed to $REMOTE."
