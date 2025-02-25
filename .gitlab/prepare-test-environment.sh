#!/bin/bash

# In Gitlab, DD_* variables are set because the build runner is instrumented with Datadog telemetry
# To have a pristine environment for the tests, these variables are saved before the test run and restored afterwards

gitlabVariables=("DD_SERVICE" "DD_ENTITY_ID" "DD_SITE" "DD_ENV" "DD_DATACENTER" "DD_PARTITION" "DD_CLOUDPROVIDER")

function save_and_clean() {
  for VARIABLE in "${gitlabVariables[@]}"
  do
    echo "export $VARIABLE=${!VARIABLE}" >> pretest.env
    unset "$VARIABLE"
  done
}

function restore() {
    source pretest.env
}

if [ "$1" == "save" ] ; then
  save_and_clean
elif [ "$1" == "restore" ]; then
  restore
else
  echo "Unknown argument $1"
fi
