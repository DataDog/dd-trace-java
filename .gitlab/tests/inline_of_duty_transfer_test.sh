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
capture_root=workspace/build/inline-of-duty/captures
mkdir -p "$capture_root/123/456/run/test"
printf 'worker one\n' > "$capture_root/123/456/run/test/hotspot_pid1.log"
bash "$script_directory/inline_of_duty_transfer.sh" upload
archives=("$TEST_S3_ROOT"/test-bucket/jit-inline-captures/123/*.tar.gz)
[[ ${#archives[@]} -eq 1 && -f "${archives[0]}" ]]
rm -rf "$capture_root"
bash "$script_directory/inline_of_duty_transfer.sh" download
[[ $(cat "$capture_root/123/456/run/test/hotspot_pid1.log") == 'worker one' ]]

# A retried logical shard replaces its earlier job's archive.
export CI_JOB_ID=789
mkdir -p "$capture_root/123/789/run/test"
printf 'worker two\n' > "$capture_root/123/789/run/test/hotspot_pid2.log"
bash "$script_directory/inline_of_duty_transfer.sh" upload
[[ $(find "$TEST_S3_ROOT" -name '*.tar.gz' | wc -l) -eq 1 ]]
tar -tzf "${archives[0]}" | grep -q '123/789/run/test/hotspot_pid2.log'
if tar -tzf "${archives[0]}" | grep -q '123/456/'; then
  echo 'Retry retained the earlier job capture' >&2
  exit 1
fi
rm -rf "$capture_root/123/789"
bash "$script_directory/inline_of_duty_transfer.sh" upload
[[ $(find "$TEST_S3_ROOT" -name '*.tar.gz' | wc -l) -eq 1 ]]
if tar -tzf "${archives[0]}" | grep -q 'hotspot'; then
  echo 'Empty retry retained the earlier job capture' >&2
  exit 1
fi
echo 'JIT capture transfer test passed'
