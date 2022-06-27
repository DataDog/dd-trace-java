#!/bin/sh

# Absolute path to this script, e.g. /home/user/bin/foo.sh
SCRIPT_PATH=$(readlink -f "$0")
# Absolute path this script is in, thus /home/user/bin
SCRIPT_DIR=$(dirname "${SCRIPT_PATH}")

if [ -z ${CI} ] ; then
  echo "Running manually"
  cp ${SCRIPT_DIR}/../dd-java-agent/build/libs/dd-java-agent.jar ${SCRIPT_DIR}
else
  echo "Running on CI"
  cp ${SCRIPT_DIR}/../workspace/dd-java-agent/build/libs/*.jar ${SCRIPT_DIR}
  mv ${SCRIPT_DIR}/*.jar ${SCRIPT_DIR}/dd-java-agent.jar
fi

cd ${SCRIPT_DIR}
IMAGE_NAME=${1}
IMAGE_TAG=${2}
docker build . -t "${IMAGE_NAME}"
docker tag "${IMAGE_NAME}" "${IMAGE_NAME}:${IMAGE_TAG}"
docker push "${IMAGE_NAME}"
docker push "${IMAGE_NAME}:${IMAGE_TAG}"
