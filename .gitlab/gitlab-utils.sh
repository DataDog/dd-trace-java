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

# A subset of ansi color/formatting codes https://misc.flogisoft.com/bash/tip_colors_and_formatting
export TEXT_RED="\e[31m"
export TEXT_GREEN="\e[32m"
export TEXT_YELLOW="\e[33m"
export TEXT_BLUE="\e[34m"
export TEXT_MAGENTA="\e[35m"
export TEXT_CYAN="\e[36m"
export TEXT_CLEAR="\e[0m"
export TEXT_BOLD="\e[1m"