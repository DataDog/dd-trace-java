#!/usr/bin/env bash

set -ex

export DD_HOSTNAME="$(hostname)-${SCENARIO//_/-}"
if [ -z "$DD_API_KEY" ]; then
  export DD_API_KEY=$(aws ssm get-parameter --region us-east-1 --name ci.$CI_PROJECT_NAME.dd_api_key --with-decryption --query "Parameter.Value" --out text)
fi

cat <<EOT > /etc/datadog-agent/datadog.yaml
api_key: $DD_API_KEY
hostname: $DD_HOSTNAME
site: datadoghq.com

tags:
  - bp_app:debugger-petclinic
  - bp_branch:$CI_COMMIT_REF_NAME
  - bp_commit_sha:$CI_COMMIT_SHORT_SHA
  - bp_project:dd-trace-java
  - ci_job_id:$CI_JOB_ID
  - ci_job_name:$CI_JOB_NAME
  - ci_pipeline_id:$CI_PIPELINE_ID
  - scenario:$SCENARIO
EOT
