#!/usr/bin/env bash

# Save all important reports into (project-root)/reports
# This folder will be saved by gitlab and available after test runs.

set -e
#Enable '**' support
shopt -s globstar

REPORTS_DIR=./reports
MOVE=false
DELETE=false

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
    project_to_save=$1
    report_path=$REPORTS_DIR/$project_to_save
    if [ "$DELETE" = true ]; then
      echo "deleting reports for $project_to_save"
      rm -rf workspace/$project_to_save/build/reports/* || true
      rm -rf workspace/$project_to_save/build/hs_err_pid*.log || true
      rm -rf workspace/$project_to_save/build/javacore*.txt || true
    elif [ "$MOVE" = true ]; then
      echo "moving reports for $project_to_save"
      mkdir -p $report_path
      mv -f workspace/$project_to_save/build/reports/* $report_path/ || true
      mv -f workspace/$project_to_save/build/hs_err_pid*.log $report_path/ || true
      mv -f workspace/$project_to_save/build/javacore*.txt $report_path/ || true
    else
      echo "copying reports for $project_to_save"
      mkdir -p $report_path
      cp -r workspace/$project_to_save/build/reports/* $report_path/ 2>/dev/null || true
      cp workspace/$project_to_save/build/hs_err_pid*.log $report_path/ 2>/dev/null || true
      cp workspace/$project_to_save/build/javacore*.txt $report_path/ 2>/dev/null || true
      cp workspace/$project_to_save/build/dumps/*.* $report_path/ 2>/dev/null || true
    fi
}

shopt -s globstar

for report_path in workspace/**/build/reports; do
    report_path=${report_path//workspace\//}
    report_path=${report_path//\/build\/reports/}
    process_reports $report_path
done

tar -czf reports.tar $REPORTS_DIR
