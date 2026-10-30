#!/usr/bin/env bash
set -euo pipefail

script_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
test_root=$(mktemp -d)
trap 'rm -rf "$test_root"' EXIT
mkdir -p "$test_root/bin" "$test_root/project"
cat > "$test_root/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == s3 ]] || exit 2
operation=$2
source=${3:-}
destination=${4:-}
local_path() {
  if [[ "$1" == s3://* ]]; then
    printf '%s/%s' "$TEST_S3_ROOT" "${1#s3://}"
  else
    printf '%s' "$1"
  fi
}
case "$operation" in
  cp)
    source=$(local_path "$source")
    destination=$(local_path "$destination")
    if [[ "${5:-}" == --recursive ]]; then
      mkdir -p "$destination"
      cp -R "$source"/. "$destination"/
    else
      mkdir -p "$(dirname "$destination")"
      cp "$source" "$destination"
    fi
    ;;
  *) exit 2 ;;
esac
EOF
chmod +x "$test_root/bin/aws"
export PATH="$test_root/bin:$PATH"
export TEST_S3_ROOT="$test_root/s3"
export CI_PIPELINE_ID=123 CI_JOB_ID=456 CI_JOB_NAME='test_inst: [21, 1/8]'
export TEST_COUNTS_S3_BUCKET=test-bucket
cd "$test_root/project"
facts=workspace/build/reports/inline-of-duty/aggregation.json
mkdir -p "$(dirname "$facts")"
printf '{"source_prefix":"456/run"}\n' > "$facts"
bash "$script_directory/inline_of_duty_transfer.sh" upload
uploaded=("$TEST_S3_ROOT"/test-bucket/jit-inline-facts/123/*.json)
[[ ${#uploaded[@]} -eq 1 && -f "${uploaded[0]}" ]]
bash "$script_directory/inline_of_duty_transfer.sh" download
downloaded=workspace/build/inline-of-duty/facts/123
[[ $(cat "$downloaded"/*.json) == '{"source_prefix":"456/run"}' ]]

# A retried logical shard replaces its earlier job's archive.
export CI_JOB_ID=789
printf '{"source_prefix":"789/run"}\n' > "$facts"
bash "$script_directory/inline_of_duty_transfer.sh" upload
[[ $(find "$TEST_S3_ROOT" -name '*.json' | wc -l) -eq 1 ]]
if grep -q '456/run' "${uploaded[0]}"; then
  echo 'Retry retained the earlier job capture' >&2
  exit 1
fi
grep -q '789/run' "${uploaded[0]}"
rm -f "$facts"
bash "$script_directory/inline_of_duty_transfer.sh" upload
[[ $(find "$TEST_S3_ROOT" -name '*.json' | wc -l) -eq 1 ]]
if grep -q '789/run' "${uploaded[0]}"; then
  echo 'Empty retry retained the earlier job capture' >&2
  exit 1
fi
grep -q '"missing":true' "${uploaded[0]}"
echo 'JIT aggregation fact transfer test passed'
