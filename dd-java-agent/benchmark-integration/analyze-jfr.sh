#!/usr/bin/env bash
#
# Analyze a perf-test JFR recording and print the tracer-overhead split that our methodology cares
# about: self-time (and sampled allocation) bucketed by thread role (foreground request threads vs
# background agent threads) and by code owner (instrumentation vs tracer-core vs app/JDK).
#
# This encodes the analysis so the numbers are trustworthy to someone running it blindly -- the tool
# emits the meaningful split, not a raw JFR to hand-parse.
#
# Usage:   ./analyze-jfr.sh /tmp/perf_<label>.jfr
# Requires: the JDK 'jfr' CLI (ships with the JDK) and awk.

set -euo pipefail

jfr_file="${1:-}"
if [ -z "$jfr_file" ] || [ ! -f "$jfr_file" ]; then
  echo "usage: ./analyze-jfr.sh <recording.jfr>" >&2
  exit 1
fi
# The jfr CLI ships with JDK 11+. If it is not already on PATH, try the JDK env vars.
if ! command -v jfr >/dev/null 2>&1; then
  for jh in "${BENCH_JAVA_HOME:-}" "${JAVA_17_HOME:-}" "${JAVA_21_HOME:-}" "${JAVA_HOME:-}"; do
    if [ -n "$jh" ] && [ -x "$jh/bin/jfr" ]; then PATH="$jh/bin:$PATH"; break; fi
  done
fi
if ! command -v jfr >/dev/null 2>&1; then
  echo "ERROR: the 'jfr' CLI was not found (ships with JDK 11+); set BENCH_JAVA_HOME to a JDK 17+." >&2
  exit 1
fi

# Shared awk classifier. Reads a stream of "THREAD<TAB>TOPFRAME" lines and prints the bucketed
# breakdown. Foreground = servlet-container request threads; background = agent + JFR/GC threads.
# Owner: instrumentation (datadog.trace.instrumentation.*) vs core (other datadog.*) vs app/other.
classify_awk='
function role(t) {
  if (t ~ /^http-nio/ || t ~ /^http-bio/ || t ~ /exec-/ || t ~ /^tomcat/ || t ~ /^XNIO/ || t ~ /^reactor-http/ || t ~ /^qtp/)
    return "foreground";
  if (t ~ /^dd-/ || t ~ /^dd\./ || t ~ /datadog/ || t ~ /^JFR/ || t ~ /Flight Recorder/ || t ~ /^GC / || t ~ /G1 / || t ~ /Reference Handl/)
    return "background";
  return "other";
}
function owner(f) {
  if (f ~ /^datadog\.trace\.instrumentation\./) return "instrumentation";
  if (f ~ /^datadog\./) return "core";
  return "app";
}
{
  total++;
  r = role($1); o = owner($2);
  role_tot[r]++;
  if (o == "instrumentation" || o == "core") {
    tracer_tot++;
    split_ct[r SUBSEP o]++;
  }
}
END {
  printf "  samples total: %d   tracer (instrumentation+core): %d (%.1f%% of all)\n",
    total, tracer_tot, total ? 100.0*tracer_tot/total : 0;
  printf "  by thread role:  foreground=%d  background=%d  other=%d\n",
    role_tot["foreground"]+0, role_tot["background"]+0, role_tot["other"]+0;
  printf "\n  tracer self-%s split (share of tracer, and of all samples):\n", METRIC;
  printf "    %-28s %10s %10s\n", "bucket", "of-tracer", "of-all";
  emit(tracer_tot, total, "foreground", "instrumentation");
  emit(tracer_tot, total, "foreground", "core");
  emit(tracer_tot, total, "background", "instrumentation");
  emit(tracer_tot, total, "background", "core");
  emit(tracer_tot, total, "other",      "instrumentation");
  emit(tracer_tot, total, "other",      "core");
}
function emit(tt, all, r, o,   c) {
  c = split_ct[r SUBSEP o] + 0;
  if (c == 0) return;
  printf "    %-28s %9.1f%% %9.1f%%\n", r"/"o, tt ? 100.0*c/tt : 0, all ? 100.0*c/all : 0;
}
'

# jfr print emits an event block per sample; the first stackTrace frame line after the event is the
# leaf. We reduce each ExecutionSample to "sampledThread<TAB>leafFrame". The sampledThread field and
# the stackTrace appear as fields; parse defensively across JDK jfr output formatting.
extract_stream() {
  local event="$1"
  jfr print --events "$event" "$jfr_file" 2>/dev/null | awk '
    /sampledThread = / { thr=$0; sub(/.*sampledThread = /,"",thr); sub(/ .*/,"",thr); gsub(/"/,"",thr); next }
    /eventThread = /   { if (thr=="") { thr=$0; sub(/.*eventThread = /,"",thr); sub(/ .*/,"",thr); gsub(/"/,"",thr) } next }
    /stackTrace = \[/  { want=1; next }
    want==1 {
      leaf=$1; sub(/\(.*/,"",leaf);           # strip "(params)"
      gsub(/^[ \t]+/,"",leaf);
      if (leaf != "" && leaf != "...") { print thr "\t" leaf }
      want=0; thr="";
    }
  '
}

echo "== JFR analysis: $jfr_file =="
echo
echo "[CPU] jdk.ExecutionSample"
cpu_stream=$(extract_stream jdk.ExecutionSample)
if [ -n "$cpu_stream" ]; then
  echo "$cpu_stream" | awk -F'\t' -v METRIC="cpu" "$classify_awk"
else
  echo "  (no ExecutionSample events found)"
fi

echo
echo "[ALLOC] jdk.ObjectAllocationSample (TLAB-sampled -- ratios authoritative, total estimated)"
alloc_stream=$(extract_stream jdk.ObjectAllocationSample)
if [ -n "$alloc_stream" ]; then
  echo "$alloc_stream" | awk -F'\t' -v METRIC="alloc" "$classify_awk"
else
  echo "  (no ObjectAllocationSample events found)"
fi
