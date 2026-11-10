#!/usr/bin/env bash
# Publish the aggregate diagnostic without changing test or report job outcomes.
set -euo pipefail
set +x

report_directory="${1:-inline-of-duty-pipeline}"
branch="${CI_COMMIT_BRANCH:-}"
case "$branch" in
  ''|master|release/*|mq-working-branch-*|gh-readonly-queue/*)
    echo "JIT Inlining Report: no PR comment for this ref"
    exit 0
    ;;
esac
if [[ ! -s "$report_directory/summary.md" || ! -s "$report_directory/findings.json" ]]; then
  echo "JIT Inlining Report: no aggregate summary available to publish"
  exit 0
fi
: "${CI_PIPELINE_ID:?}" "${CI_COMMIT_SHA:?}" "${CI_PROJECT_URL:?}"
[[ "$CI_PIPELINE_ID" =~ ^[0-9]+$ && "$CI_COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]] || {
  echo "Invalid pipeline ID or commit SHA" >&2
  exit 1
}
jq -e --arg pipeline "$CI_PIPELINE_ID" --arg project "$CI_PROJECT_URL" \
  '.pipeline_artifacts.pipeline_id == $pipeline and .pipeline_artifacts.project_url == $project' \
  "$report_directory/findings.json" >/dev/null
grep -qx '<!-- inline-of-duty -->' "$report_directory/summary.md"

repository=DataDog/dd-trace-java
comment_tmp=$(mktemp -d)
cleanup() {
  if [[ -n "${GH_TOKEN:-}" ]]; then
    dd-octo-sts revoke -t "$GH_TOKEN" >/dev/null || echo "Failed to revoke GitHub token" >&2
  fi
  rm -rf "$comment_tmp"
}
# Do not use or revoke any inherited developer credential.
unset GH_TOKEN GITHUB_TOKEN
trap cleanup EXIT
export GH_HOST=github.com
GH_TOKEN=$(dd-octo-sts token --scope "$repository" --policy self.gitlab.benchmarks.post-pr-comment)
export GH_TOKEN

# Match the exact tested revision rather than guessing a PR from the branch alone.
gh pr list --repo "$repository" --state open --head "$branch" --limit 100 \
  --json number,headRefOid > "$comment_tmp/pulls.json"
jq --arg sha "$CI_COMMIT_SHA" '[.[] | select(.headRefOid == $sha)]' \
  "$comment_tmp/pulls.json" > "$comment_tmp/matching.json"
matching_count=$(jq 'length' "$comment_tmp/matching.json")
if [[ "$matching_count" != 1 ]]; then
  echo "JIT Inlining Report: expected one open PR at the tested revision, found $matching_count; skipping"
  exit 0
fi
pr_number=$(jq -r '.[0].number' "$comment_tmp/matching.json")
[[ "$pr_number" =~ ^[0-9]+$ ]]
gh api --paginate --slurp "repos/$repository/issues/$pr_number/comments?per_page=100" \
  > "$comment_tmp/comments.json"
# Never edit a human-authored comment containing a copied report marker.
jq '[.[][] | select(.user.type == "Bot" and ((.body // "") | startswith("<!-- inline-of-duty -->\n")))]
    | sort_by(.id) | last // empty' "$comment_tmp/comments.json" > "$comment_tmp/managed.json"
if [[ -s "$comment_tmp/managed.json" ]]; then
  previous_pipeline=$(jq -r 'try (.body | capture("<!-- jit-inlining-report pipeline=(?<id>[0-9]+) -->").id) catch "0"' \
    "$comment_tmp/managed.json")
  if [[ -n "$previous_pipeline" && "$previous_pipeline" -gt "$CI_PIPELINE_ID" ]]; then
    echo "JIT Inlining Report: a newer pipeline already published; skipping"
    exit 0
  fi
fi
# The head can change while the aggregate or comment job is running.
gh pr view "$pr_number" --repo "$repository" --json state,headRefOid > "$comment_tmp/head.json"
if ! jq -e --arg sha "$CI_COMMIT_SHA" '.state == "OPEN" and .headRefOid == $sha' \
  "$comment_tmp/head.json" >/dev/null; then
  echo "JIT Inlining Report: PR closed or head changed; skipping"
  exit 0
fi
{
  cat "$report_directory/summary.md"
  printf '\n<!-- jit-inlining-report pipeline=%s -->\n' "$CI_PIPELINE_ID"
} > "$comment_tmp/body.md"
jq -n --rawfile body "$comment_tmp/body.md" '{body: $body}' > "$comment_tmp/payload.json"
if [[ -s "$comment_tmp/managed.json" ]]; then
  comment_id=$(jq -r '.id' "$comment_tmp/managed.json")
  [[ "$comment_id" =~ ^[0-9]+$ ]]
  gh api --silent --method PATCH "repos/$repository/issues/comments/$comment_id" --input "$comment_tmp/payload.json"
  echo "JIT Inlining Report: updated PR #$pr_number comment"
else
  gh api --silent --method POST "repos/$repository/issues/$pr_number/comments" --input "$comment_tmp/payload.json"
  echo "JIT Inlining Report: created PR #$pr_number comment"
fi
