#!/usr/bin/env bash
set -eu

if [[ "${DOCKER_TLS_VERIFY}" = 1 ]]; then
    TLS_ARGS="
        --secure
        --server-key ${DOCKER_CERT_PATH}/server-key.pem
        --server-cert ${DOCKER_CERT_PATH}/server-cert.pem
        --remote-key ${DOCKER_CERT_PATH}/remote-key.pem
        --remote-cert ${DOCKER_CERT_PATH}/remote-cert.pem
        --remote-ca ${DOCKER_CERT_PATH}/remote-ca.pem
    "
fi

exec autoforward \
  --port 60906 \
  --remote "${FORWARDED_DOCKER_HOST}" \
  --forward remote-docker \
  ${TLS_ARGS:-}
