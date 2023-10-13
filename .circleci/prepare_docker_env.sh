#!/usr/bin/env bash
set -eux

{
  echo "export FORWARDED_DOCKER_HOST=${DOCKER_HOST}"
  echo "export DOCKER_HOST=tcp://localhost:60906"
  echo "export TESTCONTAINERS_HOST_OVERRIDE=localhost"
  echo "export TESTCONTAINERS_RYUK_DISABLED=true"

  # DOCKER_CERT_PATH is provided only if DLC is enabled.
  if [[ -n "${DOCKER_CERT_PATH:-}" ]]; then
    echo "export DOCKER_CERT_PATH=${DOCKER_CERT_PATH}"
    echo "export DOCKER_TLS_VERIFY=1"
  else
    echo "export DOCKER_TLS_VERIFY=0"
  fi
} >> "${BASH_ENV}"

if [[ -n ${DOCKER_CERT_PATH:-} ]]; then
  cd "${DOCKER_CERT_PATH}"
  mv key.pem remote-key.pem
  mv cert.pem remote-cert.pem
  mv ca.pem remote-ca.pem

  # Generate temporary certificates for our proxy.
  openssl genrsa -out ca-key.pem 4096
  openssl req -new -x509 -days 365 -key ca-key.pem -sha256 -subj "/C=US/CN=localhost/emailAddress=admin@datadoghq.com" -out ca.pem
  openssl genrsa -out server-key.pem 4096
  openssl req -subj "/CN=localhost" -sha256 -new -key server-key.pem -out server.csr
  echo subjectAltName = DNS:localhost,IP:10.10.10.20,IP:127.0.0.1 >> extfile.cnf
  echo extendedKeyUsage = serverAuth >> extfile.cnf
  openssl x509 -req -days 365 -sha256 -in server.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial -out server-cert.pem -extfile extfile.cnf
  openssl genrsa -out key.pem 4096
  openssl req -subj '/CN=client' -new -key key.pem -out client.csr
  echo extendedKeyUsage = clientAuth > extfile-client.cnf
  openssl x509 -req -days 365 -sha256 -in client.csr -CA ca.pem -CAkey ca-key.pem -CAcreateserial -out cert.pem -extfile extfile-client.cnf
  rm -v client.csr server.csr extfile.cnf extfile-client.cnf
  chmod -v 0400 ca-key.pem key.pem server-key.pem
  chmod -v 0444 ca.pem server-cert.pem cert.pem
fi
