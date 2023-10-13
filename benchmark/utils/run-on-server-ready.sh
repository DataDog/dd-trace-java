#!/usr/bin/env bash
set -eu

url=$1
command=$2
# wait for an HTTP server to come up and runs the selected command
while true; do
  if [[ $(curl -fso /dev/null -w "%{http_code}" "${url}") = 200 ]]; then
    bash -c "${command}"
  fi
done
