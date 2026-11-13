#!/usr/bin/env bash

set -ex

export K6_STATSD_ENABLE_TAGS="true"

export COMMIT_DATE=$(date -d"$CI_COMMIT_TIMESTAMP" +%s)
export CI_JOB_DATE=$(date +%s)
export CPU_MODEL=$(cat "$ARTIFACTS_DIR/lscpu.txt" | grep -Eo 'Model name: .*' | sed 's/Model name://' | awk '{$1=$1;print}')

echo ""
echo "------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------"
echo ""
echo "You can monitor macrobenchmark progress in https://ddstaging.datadoghq.com/dashboard/xu5-cec-t4g?tpl_var_bp_branch%5B0%5D=$CI_COMMIT_REF_NAME&tpl_var_bp_commit_sha%5B0%5D=$CI_COMMIT_SHORT_SHA&tpl_var_ci_pipeline_id%5B0%5D=$CI_PIPELINE_ID"
echo ""
echo "------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------"
echo ""

BRANCH="master"
if [ "$BUILD_TYPE" == "candidate" ]; then
  BRANCH="$CI_COMMIT_REF_NAME"
fi
# Versions of the tracers
BASELINE_VERSION=""
BASELINE_AGENT_PATH="$(pwd)/tracers/dd-java-agent-baseline.jar"
if [[ -s "${BASELINE_AGENT_PATH}" ]]; then
  BASELINE_VERSION=$(java -jar "${BASELINE_AGENT_PATH}")
fi
COMMIT_SHA=""
if [ "$BUILD_TYPE" == "baseline" ]; then
  COMMIT_SHA=${BASELINE_VERSION:(-10)}
else
  COMMIT_SHA=$CI_COMMIT_SHA
fi

# used to evaluate scenario duration
#k6 run \
#    --no-usage-report \
#    --out statsd \
#    --out csv=$ARTIFACTS_DIR/k6-output.csv \
#    "$REPOSITORY_ROOT/.gitlab/benchmarks/steps/script.js"

k6 run \
    --no-usage-report \
    --out statsd \
    --out json=/dev/stdout \
    "$REPOSITORY_ROOT/.gitlab/benchmarks/steps/script.js" | benchmark_analyzer convert --framework=k6streaming \
    --extra-params="{\
        \"baseline_or_candidate\":\"$BUILD_TYPE\", \
        \"cpu_model\":\"$CPU_MODEL\", \
        \"ci_job_date\":\"$CI_JOB_DATE\", \
        \"ci_job_id\":\"$CI_JOB_ID\", \
        \"ci_pipeline_id\":\"$CI_PIPELINE_ID\", \
        \"git_commit_sha\":\"$COMMIT_SHA\", \
        \"git_commit_date\":\"$COMMIT_DATE\", \
        \"git_branch\":\"$BRANCH\"\
    }" \
    --metrics="[ \
      'agg_http_req_duration_min', \
      'agg_http_req_duration_p50', \
      'agg_http_req_duration_p75', \
      'agg_http_req_duration_p99', \
      'throughput' \
    ]" \
    --outpath="$ARTIFACTS_DIR/petclinic-${BUILD_TYPE}.converted.json" /dev/stdin
