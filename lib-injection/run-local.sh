#!/bin/sh

# Absolute path to this script, e.g. /home/user/bin/foo.sh
SCRIPT_PATH=$(readlink -f "$0")
# Absolute path this script is in, thus /home/user/bin
SCRIPT_DIR=$(dirname "${SCRIPT_PATH}")

cd ${SCRIPT_DIR}

if ! [[ "$(kind get clusters)" =~ "dd-trace-java" ]] ;  then
    kind create cluster --name dd-trace-java --config kind-config.yaml
fi

kubectl apply -f dd-apm-test-agent-config.yaml
kubectl rollout status daemonset/datadog-agent
sleep 1
POD_NAME=$(kubectl get pods -l app=datadog-agent -o name)
kubectl wait $POD_NAME --for condition=ready

mkdir -p ../dd-java-agent/build/libs
wget -O ../dd-java-agent/build/libs/dd-java-agent.jar https://dtdg.co/latest-java-tracer

if [ -z ${CI} ] ; then
    if ! [[ "$(docker buildx ls)" =~ "dd-trace-java" ]] ;  then
        docker buildx create --name dd-trace-java
    fi
fi

docker buildx use dd-trace-java
export BUILDX_PLATFORMS=`docker buildx imagetools inspect --raw busybox:latest | jq -r 'reduce (.manifests[] | [ .platform.os, .platform.architecture, .platform.variant ] | join("/") | sub("\\/$"; "")) as $item (""; . + "," + $item)' | sed 's/,//'`

./build.sh docker.io/${1}/dd-java-agent-init local

kubectl apply -f app-config.yaml
kubectl wait pod/my-app --for condition=ready --timeout=5m

sleep 5
curl http://localhost:18126/test/traces