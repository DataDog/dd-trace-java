#!/usr/bin/env bash
set -eu

url=$1
command=$2
exit_code=0

cleanup() {
  # run the exit command
  bash -c "${command}"
  exit $exit_code
}

trap cleanup EXIT ERR INT TERM

echo "Waiting for the HTTP server on ${url} to be up"
while true; do
  if [[ $(curl -fso /dev/null -w "%{http_code}" "${url}") = 200 ]]; then
    break
  fi
done
echo "Server is up! Starting k6 load test, log is in ${LOGS_DIR}/k6.log..."

# run the k6 benchmark and store the result as JSON
k6 run k6.js --out "json=${OUTPUT_DIR}/k6_$(date +%s).json" > "${LOGS_DIR}/k6.log" 2>&1
exit_code=$?

echo "k6 load test done!"
