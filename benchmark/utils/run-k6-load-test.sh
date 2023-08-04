#!/usr/bin/env bash
set -eu

url=$1
output=$2
command=$3
exit_code=0

cleanup() {
  # run the exit command
  bash -c "${command}"
  exit $exit_code
}

trap cleanup EXIT ERR INT TERM

# wait for the HTTP server to be up
while true; do
  if [[ $(curl -fso /dev/null -w "%{http_code}" "${url}") = 200 ]]; then
    break
  fi
done

# run the k6 benchmark and store the result as JSON
k6 run k6.js --out "json=${output}/k6_$(date +%s).json" &>>"${output}/k6.log"

exit_code=$?
