#!/usr/bin/env bash
# Starts or stops a single-node KRaft broker (no agent; it is never part of what's measured).
#
#   ./broker.sh start    format storage on first use, start, wait until it serves
#   ./broker.sh stop
#
# BROKER_HEAP (default 1g) sizes the broker JVM.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_kafka

data_dir="$WORK_DIR/kafka-data"
config="$WORK_DIR/server.properties"
pid_file="$WORK_DIR/broker.pid"
broker_log="$WORK_DIR/broker.log"

start() {
  if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
    log "broker already running (pid $(cat "$pid_file"))"
    return
  fi
  if [ ! -f "$data_dir/meta.properties" ]; then
    sed "s|^log.dirs=.*|log.dirs=$data_dir|" "$KAFKA_HOME/config/kraft/server.properties" > "$config"
    "$KAFKA_HOME/bin/kafka-storage.sh" format -t "$("$KAFKA_HOME/bin/kafka-storage.sh" random-uuid)" \
      -c "$config" >&2
  fi
  KAFKA_HEAP_OPTS="-Xms${BROKER_HEAP:-1g} -Xmx${BROKER_HEAP:-1g}" \
    nohup "$KAFKA_HOME/bin/kafka-server-start.sh" "$config" > "$broker_log" 2>&1 &
  echo $! > "$pid_file"
  for _ in $(seq 1 60); do
    if grep -q "Kafka Server started" "$broker_log" 2>/dev/null; then
      log "broker started (pid $(cat "$pid_file")), log: $broker_log"
      return
    fi
    sleep 1
  done
  log "ERROR: broker did not start within 60s; see $broker_log"
  exit 1
}

stop() {
  "$KAFKA_HOME/bin/kafka-server-stop.sh" >/dev/null 2>&1 || true
  if [ -f "$pid_file" ]; then
    local pid
    pid="$(cat "$pid_file")"
    for _ in $(seq 1 30); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    rm -f "$pid_file"
  fi
  log "broker stopped"
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  *) echo "usage: $0 start|stop" >&2; exit 1 ;;
esac
