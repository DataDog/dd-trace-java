#!/bin/bash

set +e  # Disable exiting from testagent response failure
SUMMARY_RESPONSE=$(curl -s -w "\n%{http_code}" -o summary_response.txt "http://${CI_AGENT_HOST}:8126/test/trace_check/summary")
set -e
SUMMARY_RESPONSE_CODE=$(echo "$SUMMARY_RESPONSE" | awk 'END {print $NF}')

if [[ SUMMARY_RESPONSE_CODE -eq 200 ]]; then
  echo "APM Test Agent is running. (HTTP 200)"
elif [[ -n "$CI_USE_TEST_AGENT" ]]; then
  echo "APM Test Agent failed to start, had an error, or exited early."
  cat summary_response.txt
  exit 1
else
  echo "APM Test Agent is not running and was not used for testing. No checks failed."
  exit 0
fi

RESPONSE=$(curl -s -w "\n%{http_code}" -o response.txt "http://${CI_AGENT_HOST}:8126/test/trace_check/failures")
RESPONSE_CODE=$(echo "$RESPONSE" | awk 'END {print $NF}')

if [[ $RESPONSE_CODE -eq 200 ]]; then
  echo "All APM Test Agent Check Traces returned successful! (HTTP 200)"
  echo "APM Test Agent Check Traces Summary Results:"
  cat summary_response.txt | jq '.'
elif [[ $RESPONSE_CODE -eq 404 ]]; then
  echo "Real APM Agent running in place of TestAgent, no checks to validate!"
else
  echo "APM Test Agent Check Traces failed with response code: $RESPONSE_CODE"
  echo "Failures:"
  cat response.txt
  echo "APM Test Agent Check Traces Summary Results:"
  cat summary_response.txt | jq '.'
  exit 1
fi
