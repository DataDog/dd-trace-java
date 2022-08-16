#!/bin/bash

## HELPERS
function echoerr() {
    echo "$@" 1>&2;
}
## end HELPERS

if [ -z "${BASE_DIR}" ] ; then
    echoerr "MUST define BASE_DIR before sourcing this file"
    exit 1
fi
export SRC_DIR=${BASE_DIR}/src
export BUILD_DIR=${BASE_DIR}/build

mkdir -p ${BUILD_DIR}

if [ -z ${CI} ] ; then
    export RUNNING_LOCALLY=1
    if [ -z ${DOCKER_USERNAME} ] ; then
        echoerr "MUST set DOCKER_USERNAME to your dockerhub username"
    fi
    export INIT_DOCKER_IMAGE_REPO=docker.io/${DOCKER_USERNAME}/dd-lib-java-init
    export APP_DOCKER_IMAGE_REPO=docker.io/${DOCKER_USERNAME}/dd-lib-java-init-test-app
    export DOCKER_IMAGE_TAG=local
else
    export RUNNING_LOCALLY=0
    export INIT_DOCKER_IMAGE_REPO=ghcr.io/datadog/dd-trace-java/dd-lib-java-init
    export APP_DOCKER_IMAGE_REPO=ghcr.io/datadog/dd-trace-java/dd-lib-java-init-test-app
    if ! [ -z ${GITHUB_SHA} ] ; then
        export DOCKER_IMAGE_TAG=${GITHUB_SHA}
    fi
    if ! [ -z ${CI_COMMIT_SHA} ] ; then
        export DOCKER_IMAGE_TAG=${CI_COMMIT_SHA}
    fi
    if [ -z ${DOCKER_IMAGE_TAG} ] ; then
        echoerr "Unknown CI provider used, can't determine git commit hash"
        exit 1
    fi
    export DD_API_KEY=apikey
    export DD_APP_KEY=appkey
fi
export USE_ADMISSION_CONTROLLER=0
export USE_UDS=0

## MODIFIERS
function use-uds() {
    export USE_UDS=1
}

function use-admission-controller() {
    export USE_ADMISSION_CONTROLLER=1
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
  clusterName: lib-injection-testing
  tags: []
  # datadog.kubelet.tlsVerify should be `false` on kind and minikube
  # to establish communication with the kubelet
  kubelet:
    tlsVerify: "false"
clusterAgent:
  enabled: true
  image:
    name: ""
    tag: master
    repository: datadog/cluster-agent-dev
    pullPolicy: Always
  admissionController:
EOF
    if [ ${USE_UDS} -eq 1 ] ; then
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

    pod_name=$(kubectl get pods -l app=datadog-cluster-agent -o name)
    kubectl wait ${pod_name} --for condition=ready --timeout=5m
    sleep 5 && kubectl get pods
}

function deploy-test-agent() {
    kubectl apply -f ${SRC_DIR}/test/resources/dd-apm-test-agent-config.yaml
    kubectl rollout status daemonset/datadog
    sleep 5 && kubectl get pods -l app=datadog

    pod_name=$(kubectl get pods -l app=datadog -o name)
    kubectl wait ${pod_name} --for condition=ready
    sleep 5 && kubectl get pods -l app=datadog
}

function deploy-agents() {
    if [ ${USE_ADMISSION_CONTROLLER} -eq 1 ] ;  then
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
    admission.datadoghq.com/java-lib.custom-image: ${INIT_DOCKER_IMAGE_REPO}:${DOCKER_IMAGE_TAG}
  name: my-app
spec:
EOF
    if [ ${USE_ADMISSION_CONTROLLER} -eq 0 ]; then
        cat <<EOF >> ${BUILD_DIR}/app-config.yaml
  initContainers:
    - command:
      - sh
      - copy-lib.sh
      - /datadog-lib
      image: ${INIT_DOCKER_IMAGE_REPO}:${DOCKER_IMAGE_TAG}
      imagePullPolicy: Always
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
    - image: ${APP_DOCKER_IMAGE_REPO}:${DOCKER_IMAGE_TAG}
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
    if [ ${USE_ADMISSION_CONTROLLER} -eq 0 ]; then
        cat <<EOF >> ${BUILD_DIR}/app-config.yaml
        - name: DD_LOGS_INJECTION
          value: 'true'
        - name: JAVA_TOOL_OPTIONS
          value: '-javaagent:/datadog-lib/dd-java-agent.jar'
EOF
        if [ ${USE_UDS} -eq 0 ]; then
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
    if [ ${USE_ADMISSION_CONTROLLER} -eq 0 ]; then
        cat <<EOF >> ${BUILD_DIR}/app-config.yaml
      volumeMounts:
        - mountPath: /datadog-lib
          name: datadog-auto-instrumentation
EOF
        if [ ${USE_UDS} -eq 1 ] ; then
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
        if [ ${USE_UDS} -eq 1 ] ; then
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
        echoerr "No traces reported - ${traces}"
        exit 1
    else
        count=`jq '. | length' <<< "${traces}"`
        echo "Received ${count} traces so far"
    fi
}

function build-and-push-init-image() {
    ensure-buildx

    docker_src_dir=${SRC_DIR}/main/docker
    docker_build_dir=${BUILD_DIR}/docker

    if [ -z "${BUILDX_PLATFORMS}" ] ; then
        BUILDX_PLATFORMS=`docker buildx imagetools inspect --raw busybox:latest | jq -r 'reduce (.manifests[] | [ .platform.os, .platform.architecture, .platform.variant ] | join("/") | sub("\\/$"; "")) as $item (""; . + "," + $item)' | sed 's/,//'`
    fi

    mkdir -p ${docker_build_dir}
    cp ${docker_src_dir}/* ${docker_build_dir}/.

    if [ ${RUNNING_LOCALLY} -eq 1 ] ; then
        echo "Running locally"
        cp ${BASE_DIR}/../dd-java-agent/build/libs/dd-java-agent.jar ${docker_build_dir}
    else
        echo "Running on CI"
        cp ${BASE_DIR}/../workspace/dd-java-agent/build/libs/*.jar ${docker_build_dir}
        mv ${docker_build_dir}/*.jar ${docker_build_dir}/dd-java-agent.jar
    fi

    if [ ! -f ${docker_build_dir}/dd-java-agent.jar ] ; then
        echoerr "dd-java-agent.jar not found"
        exit 1
    fi

    cd ${docker_build_dir}
    docker buildx build --platform ${BUILDX_PLATFORMS} -t "${INIT_DOCKER_IMAGE_REPO}:${DOCKER_IMAGE_TAG}" --push .
}

function build-test-app-image() {
    export JAVA_HOME=$JAVA_11_HOME
    cd ${BASE_DIR}/application
    ./gradlew -PdockerImageRepo=${APP_DOCKER_IMAGE_REPO} -PdockerImageTag=${DOCKER_IMAGE_TAG} clean bootBuildImage
}

function push-test-app-image() {
    docker push ${APP_DOCKER_IMAGE_REPO}:${DOCKER_IMAGE_TAG}
}
