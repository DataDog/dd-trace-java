#!/bin/bash

# Absolute path to this script, e.g. /home/user/bin/foo.sh
SCRIPT_PATH=$(readlink -f "$0")
# Absolute path this script is in, thus /home/user/bin
SCRIPT_DIR=$(dirname "${SCRIPT_PATH}")

mkdir -p ${SCRIPT_DIR}/../dd-java-agent/build/libs
if [ ! -f ${SCRIPT_DIR}/../dd-java-agent/build/libs/dd-java-agent.jar ] ; then
  wget -O ${SCRIPT_DIR}/../dd-java-agent/build/libs/dd-java-agent.jar https://dtdg.co/latest-java-tracer
fi

${SCRIPT_DIR}/run.sh use-admission-controller ensure-cluster ensure-buildx reset-deploys build-and-push-init-image deploy-agents deploy-app test-for-traces
