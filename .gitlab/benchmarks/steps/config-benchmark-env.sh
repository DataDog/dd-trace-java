readonly CURRENT_PATH="$(pwd)"

# Location of the benchmark artefacts inside the checked out version
export TRACERS_PATH="${CURRENT_PATH}/tracers"
export SOURCE_PATH="${TRACERS_PATH}/source"
export REPORTS_PATH="${TRACERS_PATH}/reports"
export BENCHMARK_PATH="${SOURCE_PATH}/benchmark"
export NO_AGENT_VARIANT="no_agent"

# Versions of the tracers
export BASELINE_AGENT_PATH="${TRACERS_PATH}/dd-java-agent-baseline.jar"
if [[ -s "${BASELINE_AGENT_PATH}" ]]; then
  export BASELINE_VERSION=$(java -jar "${BASELINE_AGENT_PATH}")
fi
export CANDIDATE_AGENT_PATH="${TRACERS_PATH}/dd-java-agent-candidate.jar"
if [[ -s "${CANDIDATE_AGENT_PATH}" ]]; then
  export CANDIDATE_VERSION=$(java -jar "${CANDIDATE_AGENT_PATH}")
fi

# In CI the location of the checkout source
export WORKSPACE="${ARTIFACTS_DIR}/.."

export NPROC=$(nproc --all)

function message() {
  echo "$(date +"%T"): $1"
}
export message