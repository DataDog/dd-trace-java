#!/bin/bash

function testworkflow() {
    local EVENT_TYPE=$1
    # Get workflow name
    local TEST_PATH
    TEST_PATH=$(dirname "$(readlink -f "${BASH_SOURCE[1]}")")
    local WORKFLOW_NAME
    WORKFLOW_NAME=$(basename "$TEST_PATH")
    local WORKFLOW_FILE=.github/workflows/${WORKFLOW_NAME}.yaml
    local PAYLOAD_FILE=${TEST_PATH}/payload-${EVENT_TYPE//_/-}.json
    # Move to project root directory
    local FILE_PATH
    FILE_PATH=$(dirname "$0")
    cd "$FILE_PATH/../../../../" || exit 1
    # Check if workflow file and payload file exist
    if [ ! -f "$WORKFLOW_FILE" ]; then
        echo "Workflow file not found: $WORKFLOW_FILE"
        exit 1
    fi
    if [ ! -f "$PAYLOAD_FILE" ]; then
        echo "Payload file not found: $PAYLOAD_FILE"
        exit 1
    fi
    # Run workflow using act
    act "${EVENT_TYPE}" \
        --workflows "${WORKFLOW_FILE}" \
        --eventpath "${PAYLOAD_FILE}" \
        --container-architecture linux/amd64 \
        --secret GITHUB_TOKEN="$(gh auth token)" \
        --verbose
}
