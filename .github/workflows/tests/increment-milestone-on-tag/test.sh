#!/bin/bash
source $(dirname "$0")/../env.sh
act create --workflows .github/workflows/increment-milestone-on-tag.yaml --eventpath .github/workflows/tests/increment-milestone-on-tag/payload.json $COMMON_ACT_ARGS
