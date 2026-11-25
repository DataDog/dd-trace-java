#!/usr/bin/env bash

# Save all important reports into (project-root)/reports
# This folder will be saved by gitlab and available after test runs.

set -e
#Enable '**' support
shopt -s globstar

REPORTS_DIR=./reports
MOVE=false
DELETE=false
SKIP_TEST_HTML=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --destination)
      REPORTS_DIR="$2"
      shift # past argument
      shift # past value
      ;;
    --move)
      MOVE=true
      shift # past argument
      ;;
    --skip-test-html)
      SKIP_TEST_HTML=true
      shift
      ;;
    --delete)
      DELETE=true
      shift # past argument
      ;;
    *)
      echo "Unknown option $1"
      exit 1
      ;;
  esac
done

mkdir -p $REPORTS_DIR >/dev/null 2>&1

cp /tmp/hs_err_pid*.log $REPORTS_DIR 2>/dev/null || true
cp /tmp/java_pid*.hprof $REPORTS_DIR 2>/dev/null || true
cp /tmp/javacore.* $REPORTS_DIR 2>/dev/null || true
cp /tmp/*.trc $REPORTS_DIR 2>/dev/null || true
cp /tmp/*.dmp $REPORTS_DIR 2>/dev/null || true
cp /tmp/dd-profiler/*.jfr $REPORTS_DIR 2>/dev/null || true

function process_reports () {
    project_dir=$1
    project_to_save=${project_dir#workspace/}
    report_path=$REPORTS_DIR/$project_to_save
    if [ "$DELETE" = true ]; then
      echo "deleting reports for $project_to_save"
      rm -rf $project_dir/build/reports/* || true
      rm -rf $project_dir/build/hs_err_pid*.log || true
      rm -rf $project_dir/build/javacore*.txt || true
    elif [ "$MOVE" = true ]; then
      echo "moving reports for $project_to_save"
      mkdir -p $report_path
      mv -f $project_dir/build/reports/* $report_path/ || true
      mv -f $project_dir/build/hs_err_pid*.log $report_path/ || true
      mv -f $project_dir/build/javacore*.txt $report_path/ || true
    else
      local entry
      local -a files=()
      for entry in "$project_dir"/build/reports/*; do
        if [[ "$SKIP_TEST_HTML" = true && "${entry##*/}" = tests ]]; then
          continue
        fi
        files+=("$entry")
      done
      files+=("$project_dir"/build/hs_err_pid*.log "$project_dir"/build/javacore*.txt "$project_dir"/build/dumps/*.*)
      if (( ${#files[@]} == 0 )); then
        return
      fi
      echo "copying reports for $project_to_save"
      mkdir -p "$report_path"
      for entry in "${files[@]}"; do
        cp -r "$entry" "$report_path/" || true
      done
    fi
}

# Match the roots used by the JUnit result collector.
shopt -s nullglob

for report_path in workspace/**/build/reports buildSrc/**/build/reports build-logic/**/build/reports; do
    process_reports "${report_path%/build/reports}"
done

tar -czf reports.tar $REPORTS_DIR
