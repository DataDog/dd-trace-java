#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <startup|load|dacapo>" >&2
  exit 1
fi

readonly BENCHMARK_TYPE="$1"
readonly REPORTS_DIR="${CI_PROJECT_DIR}/reports"
readonly TYPE_DIR="${REPORTS_DIR}/${BENCHMARK_TYPE}"
readonly CANDIDATE_RAW_DIR="${TYPE_DIR}/candidate-raw"
readonly BASELINE_RAW_DIR="${TYPE_DIR}/baseline-raw"
readonly BASELINE_INFO_FILE="${TYPE_DIR}/baseline-info.env"

mkdir -p "${TYPE_DIR}" "${CANDIDATE_RAW_DIR}" "${BASELINE_RAW_DIR}"

# DEBUG LINES
echo "[benchmark-compare] BENCHMARK_TYPE=${BENCHMARK_TYPE}"
echo "[benchmark-compare] CI_PROJECT_DIR=${CI_PROJECT_DIR}"
echo "[benchmark-compare] REPORTS_DIR=${REPORTS_DIR}"
echo "[benchmark-compare] TYPE_DIR=${TYPE_DIR}"
echo "[benchmark-compare] Expecting baseline info at ${BASELINE_INFO_FILE}"

if [[ ! -f "${BASELINE_INFO_FILE}" ]]; then
  echo "[benchmark-compare] ERROR: Missing baseline info file: ${BASELINE_INFO_FILE}" >&2
  echo "[benchmark-compare] DEBUG: pwd=$(pwd)" >&2
  echo "[benchmark-compare] DEBUG: reports tree (depth 2)" >&2
  find "${REPORTS_DIR}" -maxdepth 2 -type f 2>/dev/null | sort >&2 || true
  exit 1
fi

source "${BASELINE_INFO_FILE}"
echo "[benchmark-compare] Loaded baseline-info.env:"
cat "${BASELINE_INFO_FILE}"

export MD_REPORT_ONLY_CHANGES=1
export MD_REPORT_SAMPLE_METRICS=1
export FAIL_ON_REGRESSION_THRESHOLD=20.0

readonly JOBS_API_URL="${CI_API_V4_URL}/projects/${CI_PROJECT_ID}"

job_pattern_for_type() {
  case "${BENCHMARK_TYPE}" in
    startup)
      echo '^linux-java-(spring-petclinic|insecure-bank)-microbenchmark-startup-'
      ;;
    load)
      echo '^linux-java-(spring-petclinic|insecure-bank)-microbenchmark-load-'
      ;;
    dacapo)
      echo '^linux-java-dacapo-microbenchmark-'
      ;;
    *)
      echo "Unknown benchmark type '${BENCHMARK_TYPE}'" >&2
      exit 1
      ;;
  esac
}

extract_job_meta() {
  local job_name="$1"
  python3 - "${job_name}" <<'PY'
import re
import sys

name = sys.argv[1]
for pattern in (
    r"^linux-java-(?P<app>spring-petclinic|insecure-bank)-microbenchmark-(?P<kind>startup|load)-(?P<variant>.+)$",
    r"^linux-java-dacapo-microbenchmark-(?P<variant>.+)$",
):
    m = re.match(pattern, name)
    if m:
        app = m.groupdict().get("app", "dacapo")
        variant = m.group("variant")
        print(f"{app}|{variant}")
        sys.exit(0)
print("||")
PY
}

list_matching_jobs() {
  local pipeline_id="$1"
  local pattern
  pattern="$(job_pattern_for_type)"

  curl --silent --show-error --fail \
    --header "JOB-TOKEN: ${CI_JOB_TOKEN}" \
    "${JOBS_API_URL}/pipelines/${pipeline_id}/jobs?scope[]=success&per_page=100" \
    | python3 -c '
import json
import re
import sys

pattern = re.compile(sys.argv[1])
jobs = json.load(sys.stdin)
for job in jobs:
    if not pattern.match(job["name"]):
        continue
    if not job.get("artifacts_file") or not job["artifacts_file"].get("filename"):
        continue
    print("{}|{}".format(job["id"], job["name"]))
' "${pattern}"
}

download_job_artifacts() {
  local job_id="$1"
  local output_dir="$2"
  local archive_path="${output_dir}/artifacts.zip"
  local artifacts_url="${JOBS_API_URL}/jobs/${job_id}/artifacts"
  local http_code

  mkdir -p "${output_dir}"

  http_code="$(
    curl --silent --show-error --location \
      --header "JOB-TOKEN: ${CI_JOB_TOKEN}" \
      --output "${archive_path}" \
      --write-out "%{http_code}" \
      "${artifacts_url}"
  )"

  if [[ "${http_code}" != "200" ]]; then
    rm -f "${archive_path}"
    case "${http_code}" in
      400)
        echo "ERROR: Bad request for job ${job_id} artifacts (HTTP 400)." >&2
        ;;
      401)
        echo "ERROR: Authentication required for job ${job_id} artifacts (HTTP 401)." >&2
        ;;
      403)
        echo "ERROR: Access forbidden for job ${job_id} artifacts (HTTP 403)." >&2
        ;;
      404)
        echo "ERROR: Artifacts not found for job ${job_id} (HTTP 404). Job may still be running or produced no artifacts." >&2
        ;;
      5*)
        echo "ERROR: Server/network issue while downloading artifacts for job ${job_id} (HTTP ${http_code})." >&2
        ;;
      *)
        echo "ERROR: Failed to download artifacts for job ${job_id} (HTTP ${http_code})." >&2
        ;;
    esac
    if [[ -n "${CI_PROJECT_URL:-}" ]]; then
      echo "See job details: ${CI_PROJECT_URL}/-/jobs/${job_id}" >&2
    fi
    return 1
  fi

  rm -rf "${output_dir}/unzipped"
  mkdir -p "${output_dir}/unzipped"
  unzip -qq "${archive_path}" -d "${output_dir}/unzipped"
}

post_process_startup() {
  local report_path="$1"
  local application="$2"
  local variant="$3"
  local side="$4"
  python3 - "${report_path}" "${application}" "${variant}" "${side}" <<'PY'
import json
import sys

path, app, variant, side = sys.argv[1:]
with open(path, "r", encoding="utf-8") as fin:
    report = json.load(fin)

for benchmark in report.get("benchmarks", []):
    params = benchmark.setdefault("parameters", {})
    module = params.get("module", "unknown")
    params["scenario"] = f"startup:{app}:{variant}:{module}"
    params["application"] = app
    params["variant"] = variant
    params["baseline_or_candidate"] = side

with open(path, "w", encoding="utf-8") as fout:
    json.dump(report, fout, indent=2)
PY
}

post_process_dacapo() {
  local report_path="$1"
  local benchmark_name="$2"
  local variant="$3"
  local side="$4"
  python3 - "${report_path}" "${benchmark_name}" "${variant}" "${side}" <<'PY'
import json
import sys

path, benchmark_name, variant, side = sys.argv[1:]
with open(path, "r", encoding="utf-8") as fin:
    report = json.load(fin)

for benchmark in report.get("benchmarks", []):
    params = benchmark.setdefault("parameters", {})
    params["scenario"] = f"dacapo:{benchmark_name}:{variant}"
    params["application"] = benchmark_name
    params["variant"] = variant
    params["baseline_or_candidate"] = side

with open(path, "w", encoding="utf-8") as fout:
    json.dump(report, fout, indent=2)
PY
}

post_process_load() {
  local report_path="$1"
  local application="$2"
  local variant="$3"
  local side="$4"
  python3 - "${report_path}" "${application}" "${variant}" "${side}" <<'PY'
import json
import sys

path, app, variant, side = sys.argv[1:]
with open(path, "r", encoding="utf-8") as fin:
    report = json.load(fin)

benchmarks = []
for benchmark in report.get("benchmarks", []):
    params = benchmark.setdefault("parameters", {})
    raw_scenario = str(params.get("scenario", ""))
    stage = raw_scenario.split("--")[0] if "--" in raw_scenario else raw_scenario
    if stage.endswith("warmup"):
        continue
    params["scenario"] = f"{stage}:{app}:{variant}"
    params["application"] = app
    params["variant"] = variant
    params["baseline_or_candidate"] = side
    benchmarks.append(benchmark)

report["benchmarks"] = benchmarks

with open(path, "w", encoding="utf-8") as fout:
    json.dump(report, fout, indent=2)
PY
}

convert_startup_job() {
  local source_dir="$1"
  local side="$2"
  local application="$3"
  local variant="$4"

  local startup_dir
  startup_dir="$(find "${source_dir}" -type f -name 'startup_*.csv' -print -quit | xargs dirname 2>/dev/null || true)"
  if [[ -z "${startup_dir}" || ! -d "${startup_dir}" ]]; then
    return
  fi

  local target_dir="${TYPE_DIR}/${application}/${variant}"
  local out_file="${target_dir}/benchmark-${side}.json"
  mkdir -p "${target_dir}"

  benchmark_analyzer convert \
    --framework=JavaStartup \
    --extra-params="{\"application\":\"${application}\",\"variant\":\"${variant}\",\"baseline_or_candidate\":\"${side}\"}" \
    --outpath="${out_file}" \
    "${startup_dir}"

  post_process_startup "${out_file}" "${application}" "${variant}" "${side}"
}

convert_dacapo_job() {
  local source_dir="$1"
  local side="$2"
  local variant="$3"
  local dacapo_root="${source_dir}/dacapo"

  if [[ ! -d "${dacapo_root}" ]]; then
    return
  fi

  while IFS= read -r benchmark_dir; do
    local benchmark_name
    benchmark_name="$(basename "${benchmark_dir}")"
    local target_dir="${TYPE_DIR}/${benchmark_name}/${variant}"
    local out_file="${target_dir}/benchmark-${side}.json"
    mkdir -p "${target_dir}"

    benchmark_analyzer convert \
      --framework=JavaDacapo \
      --extra-params="{\"application\":\"${benchmark_name}\",\"variant\":\"${variant}\",\"baseline_or_candidate\":\"${side}\"}" \
      --outpath="${out_file}" \
      "${benchmark_dir}"

    post_process_dacapo "${out_file}" "${benchmark_name}" "${variant}" "${side}"
  done < <(find "${dacapo_root}" -mindepth 2 -maxdepth 2 -type d)
}

convert_load_job() {
  local source_dir="$1"
  local side="$2"
  local application="$3"
  local variant="$4"

  local k6_files=()
  while IFS= read -r file; do
    k6_files+=("${file}")
  done < <(find "${source_dir}" -type f -name 'candidate-*.converted.json' ! -name '*resource*')

  if [[ "${#k6_files[@]}" -eq 0 ]]; then
    return
  fi

  local target_dir="${TYPE_DIR}/${application}/_merged_k6_results"
  local out_file="${target_dir}/benchmark-${side}.json"
  mkdir -p "${target_dir}"

  benchmark_analyzer merge \
    --mergeby="['scenario', 'application', 'variant', 'baseline_or_candidate', 'git_branch', 'git_commit_sha', 'git_commit_date', 'cpu_model', 'kernel_version']" \
    --outpath="${out_file}" \
    "${k6_files[@]}"

  post_process_load "${out_file}" "${application}" "${variant}" "${side}"
}

convert_job() {
  local source_dir="$1"
  local side="$2"
  local application="$3"
  local variant="$4"

  case "${BENCHMARK_TYPE}" in
    startup)
      convert_startup_job "${source_dir}" "${side}" "${application}" "${variant}"
      ;;
    load)
      convert_load_job "${source_dir}" "${side}" "${application}" "${variant}"
      ;;
    dacapo)
      convert_dacapo_job "${source_dir}" "${side}" "${variant}"
      ;;
  esac
}

process_pipeline() {
  local pipeline_id="$1"
  local side="$2"
  local raw_dir="$3"
  local git_sha="$4"

  local jobs_found=0
  while IFS='|' read -r job_id job_name; do
    [[ -z "${job_id}" ]] && continue
    jobs_found=$((jobs_found + 1))

    local meta application variant
    meta="$(extract_job_meta "${job_name}")"
    application="${meta%%|*}"
    variant="${meta##*|}"
    if [[ -z "${application}" || -z "${variant}" ]]; then
      continue
    fi

    local job_dir="${raw_dir}/${job_id}"
    echo "Processing ${side} job ${job_name} (#${job_id})"
    download_job_artifacts "${job_id}" "${job_dir}"
    convert_job "${job_dir}/unzipped" "${side}" "${application}" "${variant}"
  done < <(list_matching_jobs "${pipeline_id}")

  if [[ "${jobs_found}" -eq 0 ]]; then
    echo "No matching ${BENCHMARK_TYPE} jobs found in pipeline ${pipeline_id} for ${side}." >&2
    exit 1
  fi

  echo "${git_sha}" > "${TYPE_DIR}/${side}.sha"
}

build_report() {
  local metrics
  case "${BENCHMARK_TYPE}" in
    startup|dacapo)
      metrics="['execution_time']"
      ;;
    load)
      metrics="['agg_http_req_duration_p50','agg_http_req_duration_p95','throughput']"
      ;;
  esac

  benchmark_analyzer compare pairwise \
    --baseline='{"baseline_or_candidate":"baseline"}' \
    --candidate='{"baseline_or_candidate":"candidate"}' \
    --format=md \
    --metrics="${metrics}" \
    --outpath="${TYPE_DIR}/comparison-baseline-vs-candidate.md" \
    "${TYPE_DIR}"/*/*/benchmark-{baseline,candidate}.json
}

process_pipeline "${CI_PIPELINE_ID}" "candidate" "${CANDIDATE_RAW_DIR}" "${CI_COMMIT_SHA}"
process_pipeline "${BASELINE_PIPELINE_ID}" "baseline" "${BASELINE_RAW_DIR}" "${BASELINE_SHA}"
build_report

echo "Comparison report generated at ${TYPE_DIR}/comparison-baseline-vs-candidate.md"
