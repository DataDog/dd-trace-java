#!/usr/bin/env python3
"""GC pauses, safepoints and tracer CPU in a JFR recording: total and per-collection pause time,
safepoint time, the share of request-thread CPU samples with a tracer frame on the stack (grouped by
innermost tracer class), and which other threads ran tracer code.

    gc-cpu.py <recording.jfr> <label> [path/to/jfr]
"""
import collections
import os
import re
import subprocess
import sys

jfr, label = sys.argv[1], sys.argv[2]
jfrbin = sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "jfr")
UNIT = {"ns": 1e-6, "us": 1e-3, "ms": 1.0, "s": 1000.0}
def events(names, depth=0):
    return subprocess.run([jfrbin, "print", "--events", names, "--stack-depth", str(depth), jfr], capture_output=True, text=True).stdout
# GC pauses
pauses = [float(v) * UNIT[u] for v, u in re.findall(r"sumOfPauses = ([\d.]+) (ns|us|ms|s)\b", events("jdk.GarbageCollection"))]
pauses.sort()
pct = lambda p: pauses[min(len(pauses) - 1, int(p * len(pauses)))] if pauses else 0
print(f"==== {label}")
print(f"  GC: {len(pauses)} collections, total pause {sum(pauses):.0f} ms over ~40s ({sum(pauses)/400:.2f}% of wall), p50 {pct(.5):.2f} ms, p90 {pct(.9):.2f} ms, max {pauses[-1] if pauses else 0:.1f} ms")
sp = [float(v) * UNIT[u] for v, u in re.findall(r"duration = ([\d.]+) (ns|us|ms|s)\b", events("jdk.SafepointBegin"))]
print(f"  safepoints: {len(sp)}, total {sum(sp):.0f} ms")
# CPU: request-thread samples, inclusive tracer share by top tracer frame
out = events("jdk.ExecutionSample", 64)
req = 0; incl = 0; by = collections.Counter(); bg = collections.Counter()
for ev in out.split("jdk.ExecutionSample {")[1:]:
    t = re.search(r'sampledThread = "([^"]+)"', ev); thread = t.group(1) if t else "?"
    frames = re.findall(r"^\s+([\w$.<>]+)\(", ev, re.M)
    tracer = [f for f in frames if f.startswith(("datadog.", "io.opentelemetry"))]
    if "http-nio" in thread and "exec" in thread:
        req += 1
        if tracer:
            incl += 1
            by[tracer[0].rsplit(".", 1)[0].split("$")[0]] += 1   # innermost tracer class
    elif tracer:
        bg[re.sub(r"[-\d]+$", "", thread)] += 1
print(f"  request-thread CPU samples: {req}; with a tracer frame on the stack: {incl} ({100*incl/max(req,1):.1f}%)")
for cls, n in by.most_common(12): print(f"     {n:5d}  {cls}")
print("  non-request threads running tracer code:", dict(bg.most_common(6)))
