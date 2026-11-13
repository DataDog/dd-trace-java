function fetch_agent() {
  local dest=$1 target=$2
  if [[ -s "${dest}" ]]; then
    message "Tracer already downloaded at ${dest}, skipping ..."
  else
    case "${target}" in
    "current-ci-pipeline")
      cp "${WORKSPACE}/workspace/dd-java-agent/build/libs/"dd-java-agent-*.jar "${dest}"
      ;;
    "latest_release")
      curl --fail --location --silent --show-error --output ${dest} "https://github.com/DataDog/dd-trace-java/releases/download/download-latest/dd-java-agent.jar"
      ;;
    *)
      curl --fail --location --silent --show-error --output ${dest} "https://s3.us-east-1.amazonaws.com/dd-trace-java-builds/${target}/dd-java-agent.jar"
      ;;
    esac
  fi
}

function fetch_source() {
  local dest=$1 target=$2
  if [[ -d "${dest}" ]]; then
    message "Tracer source already downloaded at ${dest}, skipping ..."
  else
    mkdir -p "${dest}"
    case "${target}" in
    "current-ci-pipeline")
      cp -r "${WORKSPACE}"/* "${dest}"
      ;;
    *)
      wget -q "https://codeload.github.com/DataDog/dd-trace-java/tar.gz/refs/heads/${target}" -O - | tar -xz --strip-components=1 -C "${dest}"
      ;;
    esac
  fi
}
