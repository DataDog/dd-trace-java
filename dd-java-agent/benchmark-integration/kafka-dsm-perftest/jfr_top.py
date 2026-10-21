"""Derive top self/total hot methods from a JFR file (jdk.ExecutionSample) via `jfr print`.

usage: jfr_top.py <file.jfr> [top_n]   (needs `jfr` on PATH; stdlib only)
"""
import collections
import re
import subprocess
import sys

path = sys.argv[1]
top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 40
out = subprocess.run(
    ["jfr", "print", "--events", "jdk.ExecutionSample", "--stack-depth", "64", path],
    capture_output=True, text=True, check=True,
).stdout

self_c, total_c = collections.Counter(), collections.Counter()
samples = 0
for block in out.split("jdk.ExecutionSample")[1:]:
    m = re.search(r"stackTrace = \[(.*?)\n\s*\]", block, re.S)
    if not m:
        continue
    frames = []
    for line in m.group(1).splitlines():
        line = re.sub(r"\s+line:.*$", "", line.strip())
        if line and line != "...":
            frames.append(line)
    if not frames:
        continue
    samples += 1
    self_c[frames[0]] += 1
    for f in set(frames):
        total_c[f] += 1

print(f"# {path}: {samples} jdk.ExecutionSample samples")
for title, c in (("Top self", self_c), ("Top total (inclusive)", total_c)):
    print(f"\n## {title}")
    for f, n in c.most_common(top_n):
        print(f"{n:7d} {100.0 * n / max(samples, 1):5.1f}%  {f}")
