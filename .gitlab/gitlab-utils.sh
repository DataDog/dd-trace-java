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
