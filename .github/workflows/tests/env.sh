#!/bin/sh

# Move to project root directory
FILE_PATH=$(dirname "$0")
cd $FILE_PATH/../../../../

export COMMON_ACT_ARGS="--container-architecture linux/amd64 --secret GITHUB_TOKEN="$(gh auth token)" --verbose"
