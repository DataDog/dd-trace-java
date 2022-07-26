#!/bin/bash

if [ -z "${BASE_DIR}" ] ; then
    echo "MUST define BASE_DIR before sourcing this file"
    exit 1
fi
export SRC_DIR=${BASE_DIR}/src
export BUILD_DIR=${BASE_DIR}/build

mkdir -p ${BUILD_DIR}

if [ -z ${CI} ] ; then
    export RUNNING_LOCALLY=1
    if [ -z ${DOCKER_USERNAME} ] ; then
        echo "MUST set DOCKER_USERNAME to your dockerhub username"
    fi
    export DOCKER_IMAGE_REPO=docker.io/${DOCKER_USERNAME}/dd-java-agent-init
    export DOCKER_IMAGE_TAG=local
else
    export RUNNING_LOCALLY=0
    export DOCKER_IMAGE_REPO=ghcr.io/datadog/dd-trace-java/dd-java-agent-init
    if ! [ -z ${GITHUB_SHA} ] ; then
        export DOCKER_IMAGE_TAG=${GITHUB_SHA}
    fi
    if ! [ -z ${CI_COMMIT_SHA} ] ; then
        export DOCKER_IMAGE_TAG=${CI_COMMIT_SHA}
    fi
    if [ -z ${DOCKER_IMAGE_TAG} ] ; then
        echo "Unknown CI provider used, can't determine git commit hash"
        exit 1
    fi
    export DD_API_KEY=apikey
    export DD_APP_KEY=appkey
fi
export USE_OPERATOR=0
export USE_UDS=0

## MODIFIERS
function use-uds() {
    export USE_UDS=1
}

function use-operator() {
    export USE_OPERATOR=1
}

## FUNCTIONS
function reset-cluster() {
    if [[ "$(kind get clusters)" =~ "lib-injection-testing" ]] ;  then
        kind delete cluster --name lib-injection-testing
    fi
}

function reset-buildx() {
    if [[ "$(docker buildx ls)" =~ "lib-injection-testing" ]] ;  then
        echo "deleting docker buildx builder: lib-injection-testing"
        docker buildx rm lib-injection-testing
    fi
}

function reset-deploys() {
    reset-app
    helm uninstall datadog
    kubectl delete daemonset datadog
    kubectl delete pods -l app=datadog
}

function reset-all() {
    reset-cluster
    reset-buildx
}

function ensure-cluster() {
    if ! [[ "$(kind get clusters)" =~ "lib-injection-testing" ]] ;  then
        kind create cluster --name lib-injection-testing --config ${SRC_DIR}/test/resources/kind-config.yaml || exit 1
    fi
}

function ensure-buildx() {
    if ! [[ "$(docker buildx ls)" =~ "lib-injection-testing" ]] ;  then
        docker buildx create --name lib-injection-testing || exit 1
    fi
    docker buildx use lib-injection-testing
}

function create-operator-config() {
    cat <<EOF > ${BUILD_DIR}/operator-helm-values.yaml
targetSystem: "linux"
agents:
  enabled: false
datadog:
EOF
    if [ $RUNNING_LOCALLY -eq 1 ] ; then
        cat <<EOF >> ${BUILD_DIR}/operator-helm-values.yaml
  clusterName: local
EOF
    else
        cat <<EOF >> ${BUILD_DIR}/operator-helm-values.yaml
  clusterName: ci
EOF
    fi
    cat <<EOF >> ${BUILD_DIR}/operator-helm-values.yaml
  tags: []
  # datadog.kubelet.tlsVerify should be `false` on kind and minikube
  # to establish communication with the kubelet
  kubelet:
    tlsVerify: "false"
  logs:
    enabled: true
    containerCollectAll: false
    containerCollectUsingFiles: true
clusterAgent:
  enabled: true
  image:
    name: ""
    tag: ahmed-auto-instru
    repository: datadog/cluster-agent-dev
    pullPolicy: Always
  admissionController:
EOF
    if [ $USE_UDS -eq 1 ] ; then
        cat <<EOF >> ${BUILD_DIR}/operator-helm-values.yaml
    configMode: socket
EOF
    else
        cat <<EOF >> ${BUILD_DIR}/operator-helm-values.yaml
    configMode: hostip
EOF
    fi
}

function deploy-operator() {
    create-operator-config

    helm repo add datadog https://helm.datadoghq.com
    helm repo update

    helm install datadog --set datadog.apiKey=${DD_API_KEY} --set datadog.appKey=${DD_APP_KEY} -f ${BUILD_DIR}/operator-helm-values.yaml datadog/datadog
    sleep 5 && kubectl get pods

    POD_NAME=$(kubectl get pods -l app=datadog-cluster-agent -o name)
    kubectl wait $POD_NAME --for condition=ready --timeout=5m
    sleep 5 && kubectl get pods
}

function deploy-test-agent() {
    kubectl apply -f ${SRC_DIR}/test/resources/dd-apm-test-agent-config.yaml
    kubectl rollout status daemonset/datadog
    sleep 5 && kubectl get pods -l app=datadog

    POD_NAME=$(kubectl get pods -l app=datadog -o name)
    kubectl wait $POD_NAME --for condition=ready
    sleep 5 && kubectl get pods -l app=datadog
}

function deploy-agents() {
    if [ $USE_OPERATOR -eq 1 ] ;  then
        deploy-operator
    fi
    deploy-test-agent   
}

function reset-app() {
    kubectl delete pods my-app
}

function create-app-config() {
    cat <<EOF > ${BUILD_DIR}/app-config.yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: my-app
    tags.datadoghq.com/env: local
    tags.datadoghq.com/service: my-app
    tags.datadoghq.com/version: local
    admission.datadoghq.com/enabled: "true"
  annotations:
    admission.datadoghq.com/java-tracer.custom-image: ${DOCKER_IMAGE_REPO}:${DOCKER_IMAGE_TAG}
  name: my-app
spec:
EOF
    if [ $USE_OPERATOR -eq 0 ]; then
        cat <<EOF >> ${BUILD_DIR}/app-config.yaml
  initContainers:
    - command:
      - sh
      - copy-lib.sh
      - /datadog-lib
      image: ${DOCKER_IMAGE_REPO}:${DOCKER_IMAGE_TAG}
      imagePullPolicy: IfNotPresent
      name: datadog-tracer-init
      resources: {}
      terminationMessagePath: /dev/termination-log
      terminationMessagePolicy: File
      volumeMounts:
      - mountPath: /datadog-lib
        name: datadog-auto-instrumentation
EOF
    fi
    cat <<EOF >> ${BUILD_DIR}/app-config.yaml
  containers:
    - image: docker.io/bdevinssureshatddog/k8s-lib-injection-app:latest
      env:
        - name: SERVER_PORT
          value: "18080"
        - name: DD_ENV
          valueFrom:
            fieldRef:
              apiVersion: v1
              fieldPath: metadata.labels['tags.datadoghq.com/env']
        - name: DD_SERVICE
          valueFrom:
            fieldRef:
              apiVersion: v1
              fieldPath: metadata.labels['tags.datadoghq.com/service']
        - name: DD_VERSION
          valueFrom:
            fieldRef:
              apiVersion: v1
              fieldPath: metadata.labels['tags.datadoghq.com/version']
EOF
    if [ $USE_OPERATOR -eq 0 ]; then
        cat <<EOF >> ${BUILD_DIR}/app-config.yaml
        - name: DD_LOGS_INJECTION
          value: 'true'
        - name: JAVA_TOOL_OPTIONS
          value: '-javaagent:/datadog-lib/dd-java-agent.jar'
EOF
        if [ $USE_UDS -eq 0 ]; then
            cat <<EOF >> ${BUILD_DIR}/app-config.yaml
        - name: DD_AGENT_HOST
          valueFrom:
            fieldRef:
              fieldPath: status.hostIP
EOF
        else
            cat <<EOF >> ${BUILD_DIR}/app-config.yaml
        - name: DD_TRACE_AGENT_URL
          value: 'unix:///var/run/datadog/apm.socket'
EOF
        fi
    fi
    cat <<EOF >> ${BUILD_DIR}/app-config.yaml
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
EOF
    if [ $USE_OPERATOR -eq 0 ]; then
        cat <<EOF >> ${BUILD_DIR}/app-config.yaml
      volumeMounts:
        - mountPath: /datadog-lib
          name: datadog-auto-instrumentation
EOF
        if [ $USE_UDS -eq 1 ] ; then
            cat <<EOF >> ${BUILD_DIR}/app-config.yaml
        - mountPath: /var/run/datadog
          name: datadog
EOF
        fi
        cat <<EOF >> ${BUILD_DIR}/app-config.yaml
  volumes:
    - emptyDir: {}
      name: datadog-auto-instrumentation
EOF
        if [ $USE_UDS -eq 1 ] ; then
            cat <<EOF >> ${BUILD_DIR}/app-config.yaml
    - hostPath:
        path: /var/run/datadog
        type: DirectoryOrCreate
      name: datadog
EOF
        fi
    fi
}

function deploy-app() {
    create-app-config

    kubectl apply -f ${BUILD_DIR}/app-config.yaml
    kubectl wait pod/my-app --for condition=ready --timeout=5m
    sleep 5 && kubectl get pods
}

function test-for-traces() {
    tmpfile=$(mktemp -t traces.XXXXXX)
    wget -O $(readlink -f "${tmpfile}") http://localhost:18126/test/traces || true
    traces=`cat ${tmpfile}`
    if [[ ${#traces} -lt 3 ]] ; then
        echo "No traces reported - ${traces}"
        exit 1
    else
        count=`jq '. | length' <<< "${traces}"`
        echo "Received ${count} traces so far"
    fi
}

function build-and-push-init-image() {
    ensure-buildx

    DOCKER_SRC_DIR=${SRC_DIR}/main/docker
    DOCKER_BUILD_DIR=${BUILD_DIR}/docker

    if [ -z "${BUILDX_PLATFORMS}" ] ; then
        BUILDX_PLATFORMS=`docker buildx imagetools inspect --raw busybox:latest | jq -r 'reduce (.manifests[] | [ .platform.os, .platform.architecture, .platform.variant ] | join("/") | sub("\\/$"; "")) as $item (""; . + "," + $item)' | sed 's/,//'`
    fi

    mkdir -p ${DOCKER_BUILD_DIR}
    cp ${DOCKER_SRC_DIR}/* ${DOCKER_BUILD_DIR}/.

    if [ ${RUNNING_LOCALLY} -eq 1 ] ; then
        echo "Running locally"
        cp ${BASE_DIR}/../dd-java-agent/build/libs/dd-java-agent.jar ${DOCKER_BUILD_DIR}
    else
        echo "Running on CI"
        cp ${BASE_DIR}/../workspace/dd-java-agent/build/libs/*.jar ${DOCKER_BUILD_DIR}
        mv ${DOCKER_BUILD_DIR}/*.jar ${DOCKER_BUILD_DIR}/dd-java-agent.jar
    fi

    cd ${DOCKER_BUILD_DIR}
    docker buildx build --platform ${BUILDX_PLATFORMS} -t "${DOCKER_IMAGE_REPO}:${DOCKER_IMAGE_TAG}" --push .
}