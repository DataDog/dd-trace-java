#!/usr/bin/env python3
"""Request-thread waits in a JFR recording: monitor-enter, park and monitor-wait time on Tomcat
request threads, grouped by what blocked and the first tracer frame (datadog.* or io.opentelemetry.*)
on the stack. Waits of idle workers for new work are left out.

    waits.py <recording.jfr> <label> [path/to/jfr]
"""
import collections
import os
import re
import subprocess
import sys

jfr, label = sys.argv[1], sys.argv[2]
jfrbin = sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "jfr")
out = subprocess.run([jfrbin, "print", "--events", "jdk.JavaMonitorEnter,jdk.ThreadPark,jdk.JavaMonitorWait",
                      "--stack-depth", "24", jfr], capture_output=True, text=True).stdout
UNIT = {"ns": 1e-6, "us": 1e-3, "ms": 1.0, "s": 1000.0}
IDLE = ("ThreadPoolExecutor.getTask", "NioEndpoint$Poller", "LinkedBlockingQueue.take", "TaskQueue.take",
        "TaskQueue.poll", "SynchronousQueue", "Acceptor.run")
totals = collections.Counter(); counts = collections.Counter(); kinds = collections.Counter()
for ev in re.split(r"\n(?=jdk\.(?:JavaMonitorEnter|ThreadPark|JavaMonitorWait) \{)", out):
    m = re.match(r"jdk\.(\w+) \{", ev.strip())
    if not m: continue
    kind = m.group(1)
    thread = re.search(r'eventThread = "([^"]+)"', ev)
    if not thread or "http-nio" not in thread.group(1) or "exec" not in thread.group(1): continue
    d = re.search(r"duration = ([\d.]+) (ns|us|ms|s)\b", ev)
    if not d: continue
    ms = float(d.group(1)) * UNIT[d.group(2)]
    frames = re.findall(r"^\s+([\w$.<>]+)\(", ev, re.M)
    if any(i in f for f in frames for i in IDLE): continue  # a worker waiting for work
    what = re.search(r"(?:monitorClass|parkedClass) = ([\w$.]+)", ev)
    owner = next((f for f in frames if f.startswith(("datadog.", "io.opentelemetry"))), None)
    site = owner or (frames[0] if frames else "?")
    key = f"{kind:16} {what.group(1) if what else '-':45} @ {site}"
    totals[key] += ms; counts[key] += 1; kinds[kind] += ms
print(f"==== {label}: request-thread waits (excluding idle worker waits) ====")
print("  by kind (ms total over 40s):", {k: round(v) for k, v in kinds.items()})
for key, ms in totals.most_common(15):
    print(f"  {ms:9.1f} ms  {counts[key]:7d}x  {key}"[:230])
