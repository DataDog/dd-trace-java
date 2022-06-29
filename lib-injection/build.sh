#!/bin/sh

# Absolute path to this script, e.g. /home/user/bin/foo.sh
SCRIPT_PATH=$(readlink -f "$0")
# Absolute path this script is in, thus /home/user/bin
SCRIPT_DIR=$(dirname "${SCRIPT_PATH}")
IMAGE_NAME=${1}
IMAGE_TAG=${2}

if [ -z ${CI} ] ; then
  echo "Running manually"
  cp ${SCRIPT_DIR}/../dd-java-agent/build/libs/dd-java-agent.jar ${SCRIPT_DIR}
else
  echo "Running on CI"
  cp ${SCRIPT_DIR}/../workspace/dd-java-agent/build/libs/*.jar ${SCRIPT_DIR}
  mv ${SCRIPT_DIR}/*.jar ${SCRIPT_DIR}/dd-java-agent.jar
fi

cat <<EOF > ${SCRIPT_DIR}/app-config.yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: my-app
  name: my-app
spec:
  initContainers:
    - image: ${IMAGE_NAME}:${IMAGE_TAG}
      command: ["sh", "copy-javaagent.sh", "/datadog"]
      name: copy-sdk
      volumeMounts:
        - name: apm-sdk-volume
          mountPath: /datadog
  containers:
    - image: docker.io/bdevinssureshatddog/k8s-lib-injection-app:latest
      env:
        - name: SERVER_PORT
          value: "18080"
        - name: JAVA_TOOL_OPTIONS
          value: "-javaagent:/datadog/dd-java-agent.jar"
        - name: DD_AGENT_HOST
          valueFrom:
            fieldRef:
              fieldPath: status.hostIP
        - name: DD_AGENT_PORT
          value: "18126"
      name: my-app
      readinessProbe:
        initialDelaySeconds: 1
        periodSeconds: 2
        timeoutSeconds: 1
        successThreshold: 1
        failureThreshold: 1
        httpGet:
          host:
          scheme: HTTP
          path: /
          port: 18080
        initialDelaySeconds: 5
        periodSeconds: 5
      ports:
        - containerPort: 18080
          hostPort: 18080
          protocol: TCP
      volumeMounts:
        - name: apm-sdk-volume
          mountPath: /datadog
  volumes:
    - name: apm-sdk-volume
      emptyDir: {}
EOF

cd ${SCRIPT_DIR}
docker buildx build --platform ${BUILDX_PLATFORMS} -t "${IMAGE_NAME}:${IMAGE_TAG}" --push .
