#!/usr/bin/env bash
set -euo pipefail

selector=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/inline_of_duty_selected.sh
check() {
  local expected=$1 enabled=$2 scope=$3 target=$4 jvm=$5 actual=0
  INLINE_OF_DUTY="$enabled" INLINE_OF_DUTY_SCOPE="$scope" GRADLE_TARGET="$target" testJvm="$jvm" \
    bash "$selector" >/dev/null 2>&1 || actual=$?
  [[ "$actual" == "$expected" ]] || {
    echo "Unexpected capture selection: $enabled $scope $target $jvm returned $actual" >&2
    exit 1
  }
}

check 0 true representative :baseTest 25
check 0 true representative :instrumentationTest 25
check 1 true representative :instrumentationLatestDepTest 25
check 1 true representative :baseTest 21
check 1 true representative :smokeTest 25
check 0 true full :smokeTest 21
check 1 true full :baseTest semeru8
check 1 false representative :baseTest 25
check 2 true invalid :baseTest 25
echo 'JIT capture selection test passed'
