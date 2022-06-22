#!/bin/sh

# Absolute path to this script, e.g. /home/user/bin/foo.sh
SCRIPT_PATH=$(readlink -f "$0")
# Absolute path this script is in, thus /home/user/bin
SCRIPT_DIR=$(dirname "${SCRIPT_PATH}")

if [ -z ${CI} ] ; then
  echo "Running manually"
  cp ${SCRIPT_DIR}/../dd-java-agent/build/libs/dd-java-agent.jar ${SCRIPT_DIR}
else
  echo "Running on gitlab"
  cp ${SCRIPT_DIR}/../workspace/dd-java-agent/build/libs/*.jar ${SCRIPT_DIR}
  mv ${SCRIPT_DIR}/*.jar ${SCRIPT_DIR}/dd-java-agent.jar
fi

cd ${SCRIPT_DIR}
IMAGE_NAME="datadog/dd-java-agent-init:${1}"
docker build . -t ${IMAGE_NAME}
docker save -o dd-java-agent-init.tar ${IMAGE_NAME}
