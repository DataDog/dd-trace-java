#!/bin/bash

# From https://docs.gitlab.com/ci/jobs/job_logs/#use-a-script-to-improve-display-of-collapsible-sections
# function for starting the section
function gitlab_section_start () {
  local section_title="${1}"
  local section_description="${2:-$section_title}"

  echo -e "section_start:`date +%s`:${section_title}[collapsed=true]\r\e[0K${section_description}"
}

# Function for ending the section
function gitlab_section_end () {
  local section_title="${1}"

  echo -e "section_end:`date +%s`:${section_title}\r\e[0K"
}

# Bootstrap Gradle through MASS, retrying upstream because the wrapper supports only one URL.
# The pinned checksum applies to either source. Call after `.gradle` and the Gradle environment are
# prepared.
function bootstrap_gradle_distribution () {
  local props="gradle/wrapper/gradle-wrapper.properties"
  local upstream_props="/tmp/gradle-wrapper.upstream.properties"
  local mass_read_host

  if [ -z "${MASS_READ_URL:-}" ]; then
    ./gradlew --version
    return
  fi

  mass_read_host="${MASS_READ_URL#https://}"
  mass_read_host="${mass_read_host%/}"

  # Derive upstream even if an earlier call already routed the URL through MASS.
  sed "/^distributionUrl=/ s|${mass_read_host}/internal/artifact/||" "$props" > "$upstream_props"

  if grep -q "^distributionUrl=.*${mass_read_host}" "$props"; then
    # Avoid nesting the MASS prefix.
    echo "Gradle distribution is already routed through ${mass_read_host}"
  else
    # Redirect because GNU and BSD `sed -i` differ.
    sed "/^distributionUrl=/ s|services.gradle.org|${mass_read_host}/internal/artifact/services.gradle.org|" "$props" > "$props.mass" &&
      mv "$props.mass" "$props"
  fi

  if ./gradlew --version; then
    rm -f "$upstream_props"
    return
  fi

  if ! grep -q "^distributionUrl=" "$upstream_props"; then
    echo -e "${TEXT_RED}Gradle distribution bootstrap failed and no upstream distributionUrl could be derived from ${props} to fall back to${TEXT_CLEAR}" >&2
    return 1
  fi

  # Keep upstream: changing the URL changes the wrapper cache key and would download twice.
  echo -e "${TEXT_YELLOW}MASS_FALLBACK gradle-distribution: ${MASS_READ_URL} could not serve the Gradle distribution, retrying via services.gradle.org${TEXT_CLEAR}" >&2
  cp "$upstream_props" "$props"
  rm -f "$upstream_props"
  ./gradlew --version
}

# A subset of ansi color/formatting codes https://misc.flogisoft.com/bash/tip_colors_and_formatting
export TEXT_RED="\e[31m"
export TEXT_GREEN="\e[32m"
export TEXT_YELLOW="\e[33m"
export TEXT_BLUE="\e[34m"
export TEXT_MAGENTA="\e[35m"
export TEXT_CYAN="\e[36m"
export TEXT_CLEAR="\e[0m"
export TEXT_BOLD="\e[1m"
