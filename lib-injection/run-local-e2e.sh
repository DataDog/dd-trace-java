#!/bin/sh

# Absolute path to this script, e.g. /home/user/bin/foo.sh
SCRIPT_PATH=$(readlink -f "$0")
# Absolute path this script is in, thus /home/user/bin
SCRIPT_DIR=$(dirname "${SCRIPT_PATH}")

cd ${SCRIPT_DIR}

if ! [[ "$(kind get clusters)" =~ "dd-trace-java" ]] ;  then
    kind create cluster --name dd-trace-java --config kind-config.yaml
fi

helm repo add datadog https://helm.datadoghq.com
helm repo update

helm install datadog --set datadog.apiKey=abcd12345678 -f e2e-helm-values.yaml datadog/datadog
sleep 5
POD_NAME=$(kubectl get pods -l app=datadog-cluster-agent -o name)
kubectl wait $POD_NAME --for condition=ready

kubectl apply -f dd-apm-test-agent-config.yaml
kubectl rollout status daemonset/datadog-agent
sleep 5
POD_NAME=$(kubectl get pods -l app=datadog-agent -o name)
kubectl wait $POD_NAME --for condition=ready

cat <<EOF > ${SCRIPT_DIR}/app-config.yaml
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: my-app
    admission.datadoghq.com/enabled: "true"
  annotations:
    admission.datadoghq.com/java-tracer.custom-image: ghcr.io/datadog/dd-trace-java/dd-java-agent-init:ab379b3da616aa1e68294467522bcc5deebd250e
  name: my-app
spec:
  containers:
    - image: docker.io/bdevinssureshatddog/k8s-lib-injection-app:latest
      name: my-app
      env:
        - name: SERVER_PORT
          value: "18080"
        - name: DD_AGENT_HOST
          valueFrom:
            fieldRef:
              fieldPath: status.hostIP
        - name: DD_AGENT_PORT
          value: "18126"
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

kubectl apply -f app-config.yaml
kubectl wait pod/my-app --for condition=ready --timeout=5m

sleep 5
curl http://localhost:18126/test/traces