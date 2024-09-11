#!/bin/bash
source $(dirname "$0")/../env.sh
act pull_request --workflows .github/workflows/add-milestone-to-pull-requests.yaml --eventpath .github/workflows/tests/add-milestone-to-pull-requests/payload.json $COMMON_ACT_ARGS
