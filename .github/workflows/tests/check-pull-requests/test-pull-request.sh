#!/bin/bash
source "$(dirname "$0")/../env.sh"
testworkflow pull_request && \
testworkflow pull_request draft && \
testworkflow pull_request no-release-notes && \
! testworkflow pull_request missing-label && \
! testworkflow pull_request title-tag
