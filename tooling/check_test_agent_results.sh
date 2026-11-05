#!/bin/bash

set -euo pipefail

if [[ "${CI_USE_TEST_AGENT:-}" != "true" ]]; then
  echo "APM Test Agent is not enabled. No trace checks requested."
  exit 0
fi

# Inspect HTTP status codes explicitly so failures retain their response bodies.
curl_args=(-sS --max-time 30 --retry 5 --retry-delay 2 --retry-connrefused -w "%{http_code}")
if ! SUMMARY_RESPONSE_CODE=$(curl "${curl_args[@]}" -o summary_response.txt "http://${CI_AGENT_HOST}:8126/test/trace_check/summary?return_all=true"); then
  echo "Could not read the APM Test Agent trace check summary."
  exit 1
fi

if [[ "$SUMMARY_RESPONSE_CODE" != "200" ]]; then
  echo "APM Test Agent summary request failed with response code: $SUMMARY_RESPONSE_CODE"
  cat summary_response.txt
  exit 1
fi

if ! jq -e -s '
  length == 1 and
  (.[0] | type == "object" and all(.[];
    type == "object" and all((.Passed_Checks, .Failed_Checks, .Skipped_Checks);
      type == "number" and . >= 0 and . == floor)))
' summary_response.txt > /dev/null; then
  echo "APM Test Agent returned an invalid trace check summary."
  cat summary_response.txt
  exit 1
fi

if ! RESPONSE_CODE=$(curl "${curl_args[@]}" -o response.txt "http://${CI_AGENT_HOST}:8126/test/trace_check/failures?return_all=true"); then
  echo "Could not read the APM Test Agent trace check failures."
  exit 1
fi

if [[ "$RESPONSE_CODE" != "200" ]]; then
  echo "APM Test Agent Check Traces failed with response code: $RESPONSE_CODE"
  echo "Failures:"
  cat response.txt
  echo "APM Test Agent Check Traces Summary Results:"
  jq '.' summary_response.txt
  exit 1
fi

if ! jq -e 'all(.[]; .Failed_Checks == 0)' summary_response.txt > /dev/null; then
  echo "APM Test Agent Check Traces failed: summary reports failing checks."
  jq '.' summary_response.txt
  exit 1
fi

if jq -e 'length == 0' summary_response.txt > /dev/null; then
  echo "APM Test Agent reported no trace checks."
else
  echo "All APM Test Agent Check Traces returned successful! (HTTP 200)"
  echo "APM Test Agent Check Traces Summary Results:"
  jq '.' summary_response.txt
fi
