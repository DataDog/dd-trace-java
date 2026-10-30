#!/usr/bin/env bash
# Offline contract tests; the fake tools cannot reach GitHub or mint real tokens.
set -euo pipefail
script_directory=$(cd "$(dirname "$0")/.." && pwd)
test_root=$(mktemp -d)
trap 'rm -rf "$test_root"' EXIT
mkdir -p "$test_root/bin"
cat > "$test_root/bin/dd-octo-sts" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$TEST_DIRECTORY/sts-calls"
if [[ "$1" == token ]]; then
  [[ "$TEST_CASE" != mint-failure ]] || exit 9
  printf 'fake-test-token\n'
fi
MOCK
cat > "$test_root/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$GH_TOKEN" == fake-test-token && "$GH_HOST" == github.com && -z "${GITHUB_TOKEN:-}" ]]
printf '%s\n' "$*" >> "$TEST_DIRECTORY/gh-calls"
case "$1 $2" in
  'pr list')
    [[ "$TEST_CASE" != lookup-failure ]] || exit 22
    case "$TEST_CASE" in
      no-pr) echo '[]' ;;
      stale) echo '[{"number":42,"headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]' ;;
      ambiguous) printf '[{"number":42,"headRefOid":"%s"},{"number":43,"headRefOid":"%s"}]\n' "$CI_COMMIT_SHA" "$CI_COMMIT_SHA" ;;
      *) printf '[{"number":42,"headRefOid":"%s"}]\n' "$CI_COMMIT_SHA" ;;
    esac ;;
  'pr view')
    case "$TEST_CASE" in
      advanced) echo '{"state":"OPEN","headRefOid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}' ;;
      closed) printf '{"state":"CLOSED","headRefOid":"%s"}\n' "$CI_COMMIT_SHA" ;;
      *) printf '{"state":"OPEN","headRefOid":"%s"}\n' "$CI_COMMIT_SHA" ;;
    esac ;;
  'api --paginate') cat "$TEST_DIRECTORY/comments.json" ;;
  'api --silent')
    [[ "$TEST_CASE" != api-failure ]] || exit 22
    # gh api --silent --method METHOD ENDPOINT --input PAYLOAD
    cp "$7" "$TEST_DIRECTORY/sent.json"
    printf '%s %s\n' "$4" "$5" > "$TEST_DIRECTORY/write"
    ;;
  *) echo "Unexpected mocked gh command" >&2; exit 90 ;;
esac
MOCK
chmod +x "$test_root/bin/gh" "$test_root/bin/dd-octo-sts"
export PATH="$test_root/bin:$PATH"
export CI_PIPELINE_ID=123 CI_COMMIT_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
export CI_PROJECT_URL=https://gitlab.example/group/project
export CI_COMMIT_BRANCH=andrea.marziali/jit-audit-poc
export GH_TOKEN=inherited-developer-token GITHUB_TOKEN=another-inherited-token
count=0
run_case() {
  export TEST_CASE="$1" TEST_DIRECTORY="$test_root/$1"
  mkdir -p "$TEST_DIRECTORY/report"
  cat > "$TEST_DIRECTORY/report/summary.md" <<'SUMMARY'
<!-- inline-of-duty -->
**JIT Inlining Report**

| Category | Methods | % |
| :-- | --: | --: |
| 🟢 Inline seen | 410 | 48.6% |
| 🔴 Refused only | 242 | 28.7% |

[Aggregate report](https://gitlab.example/group/project/-/jobs/999/artifacts/file/inline-of-duty-pipeline/index.html)
SUMMARY
  jq -n --arg pipeline "$CI_PIPELINE_ID" --arg project "$CI_PROJECT_URL" \
    '{pipeline_artifacts:{pipeline_id:$pipeline,project_url:$project}}' > "$TEST_DIRECTORY/report/findings.json"
  echo '[[]]' > "$TEST_DIRECTORY/comments.json"
  case "$1" in
    update|retry|newer|human)
      author=Bot; [[ "$1" != human ]] || author=User
      previous=122; [[ "$1" != newer ]] || previous=124
      [[ "$1" != retry ]] || previous=123
      jq -n --arg author "$author" --arg previous "$previous" \
        '[[],[{id:77,user:{type:$author},body:("<!-- inline-of-duty -->\nold summary\n<!-- jit-inlining-report pipeline="+$previous+" -->")}]]' \
        > "$TEST_DIRECTORY/comments.json"
      ;;
    mismatch) echo '{"pipeline_artifacts":{"pipeline_id":"999"}}' > "$TEST_DIRECTORY/report/findings.json" ;;
    missing) rm "$TEST_DIRECTORY/report/summary.md" ;;
  esac
  status=0
  bash "$script_directory/post_jit_inlining_comment.sh" "$TEST_DIRECTORY/report" > "$TEST_DIRECTORY/output" 2>&1 || status=$?
  case "$1" in
    api-failure|lookup-failure|mint-failure|mismatch) [[ "$status" -ne 0 ]] ;;
    *) [[ "$status" == 0 ]] ;;
  esac
  case "$1" in
    create|human) grep -qx 'POST repos/DataDog/dd-trace-java/issues/42/comments' "$TEST_DIRECTORY/write" ;;
    update|retry) grep -qx 'PATCH repos/DataDog/dd-trace-java/issues/comments/77' "$TEST_DIRECTORY/write" ;;
    *) [[ ! -e "$TEST_DIRECTORY/write" ]] ;;
  esac
  if [[ -f "$TEST_DIRECTORY/sent.json" ]]; then
    jq -e '.body | contains("<!-- jit-inlining-report pipeline=123 -->") and contains("48.6%") and contains("jobs/999/artifacts")' "$TEST_DIRECTORY/sent.json" >/dev/null
  fi
  case "$1" in
    mismatch|missing) [[ ! -e "$TEST_DIRECTORY/sts-calls" ]] ;;
    mint-failure) [[ $(wc -l < "$TEST_DIRECTORY/sts-calls") -eq 1 ]] ;;
    *) grep -qx 'token --scope DataDog/dd-trace-java --policy self.gitlab.benchmarks.post-pr-comment' "$TEST_DIRECTORY/sts-calls"
       grep -qx 'revoke -t fake-test-token' "$TEST_DIRECTORY/sts-calls" ;;
  esac
  ! grep -q 'fake-test-token\|inherited-developer-token' "$TEST_DIRECTORY/output"
  count=$((count + 1))
  echo "PASS $1"
}
for scenario in create update retry human no-pr stale ambiguous advanced closed newer api-failure lookup-failure mint-failure mismatch missing; do
  run_case "$scenario"
done
for branch in master release/v1 mq-working-branch-test gh-readonly-queue/test ''; do
  CI_COMMIT_BRANCH="$branch" bash "$script_directory/post_jit_inlining_comment.sh" "$test_root/nonexistent" >/dev/null
  count=$((count + 1))
done
echo "$count offline comment checks passed"
