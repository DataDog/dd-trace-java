#!/usr/bin/env bash
set -eu
echo "Cancelling workflow ${CIRCLE_WORKFLOW_ID}"
curl --request POST --url https://circleci.com/api/v2/workflow/$CIRCLE_WORKFLOW_ID/cancel --header "Circle-Token: ${CIRCLE_TOKEN}"
