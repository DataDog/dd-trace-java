#!/usr/bin/env bash

print_metric() {
  local label="$1"
  local raw_value="$2"
  local trimmed_value

  # Use read -rd '' to trim leading/trailing IFS whitespace (space, tab, newline)
  read -rd '' trimmed_value <<< "$raw_value" || :

  # Check if trimmed_value contains a newline character for formatting
  if [[ "$trimmed_value" == *$'\n'* ]]; then
    local indent="  "
    # Using a more robust way to handle potential leading/trailing newlines in raw_value for printf
    printf "%-35s :\n" "$label"
    printf "%s\n" "$indent${trimmed_value//$'\n'/$'\n'$indent}" # Indent and print the value on new lines
  else
    printf "%-35s : %s\n" "$label" "$trimmed_value"
  fi
}

cat_file() {
  cat "$1" 2>/dev/null || echo 'not found'
}

# Show cgroup memory usage
if [ -f /sys/fs/cgroup/cgroup.controllers ]; then
  # cgroup v2
  print_metric "cgroup v2 memory.peak" "$(cat_file /sys/fs/cgroup/memory.peak)"
  print_metric "cgroup v2 memory.max" "$(cat_file /sys/fs/cgroup/memory.max)"
  print_metric "cgroup v2 memory.high" "$(cat_file /sys/fs/cgroup/memory.high)"
  print_metric "cgroup v2 memory.current" "$(cat_file /sys/fs/cgroup/memory.current)"
  if [ -f /sys/fs/cgroup/memory.pressure ]; then
    print_metric "cgroup v2 memory.pressure" "$(cat_file /sys/fs/cgroup/memory.pressure)"
  fi
  if [ -f /sys/fs/cgroup/memory.events ]; then
    print_metric "cgroup v2 memory.events oom" "$( (grep -E '^oom\\s' /sys/fs/cgroup/memory.events | cut -d' ' -f2) 2>/dev/null || echo 'not found')"
    print_metric "cgroup v2 memory.events oom_kill" "$( (grep -E '^oom_kill\\s' /sys/fs/cgroup/memory.events | cut -d' ' -f2) 2>/dev/null || echo 'not found')"
    print_metric "cgroup v2 memory.events high" "$( (grep -E '^high\\s' /sys/fs/cgroup/memory.events | cut -d' ' -f2) 2>/dev/null || echo 'not found')"
  fi
elif [ -d "/sys/fs/cgroup/memory" ]; then
  # cgroup v1
  # Note: In cgroup v1, memory stats are typically found under /sys/fs/cgroup/memory/
  # The specific path might vary if inside a nested cgroup.
  # This script assumes it's running in a context where /sys/fs/cgroup/memory/ points to the relevant cgroup.
  print_metric "cgroup v1 memory.usage_in_bytes" "$(cat_file /sys/fs/cgroup/memory/memory.usage_in_bytes)"
  print_metric "cgroup v1 memory.limit_in_bytes" "$(cat_file /sys/fs/cgroup/memory/memory.limit_in_bytes)"
  print_metric "cgroup v1 memory.failcnt" "$(cat_file /sys/fs/cgroup/memory/memory.failcnt)"
  print_metric "cgroup v1 memory.max_usage_in_bytes" "$(cat_file /sys/fs/cgroup/memory/memory.max_usage_in_bytes)"
else
  printf "cgroup memory paths not found. Neither cgroup v2 controller file nor cgroup v1 memory directory detected.\n"
fi

print_metric "ram memory" "$( (grep MemTotal /proc/meminfo | tr -s ' ' | cut -d ' ' -f 2) 2>/dev/null || echo 'not found')"
