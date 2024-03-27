#!/usr/bin/env bash
set -eu

PNAME="$1"
LOG_FILE="$2"

while true ; do
    echo ":: $(date +"%a %b %d %H:%M:%S %Z %Y")" >> $LOG_FILE
    ps -C ${PNAME} -o %cpu,rss,pid,command >> $LOG_FILE
    sleep 1
done
