#!/usr/bin/env python3
"""Summarize C2 inlining decisions for setTag call sites from a JFR recording.

Record with (JDK 17+):
  -XX:StartFlightRecording:filename=run.jfr,jdk.CompilerInlining#enabled=true,jdk.Compilation#enabled=true,jdk.Compilation#threshold=0ms
Then:
  jfr print --json --events jdk.CompilerInlining,jdk.Compilation run.jfr > run.json
  python3 -I inlining-summary.py run.json [root-substring]

Prints (1) for each C2 compile whose method contains root-substring (default "TraceShapeBenchmark::newApi"),
the setTag/store inlining decisions with counts, and (2) the C2 code size of standalone setTag/store compiles,
which tells whether the store in use is HashIdStore or DenseIdStore.
"""
import collections
import json
import sys


def name(m):
    t = m["type"]
    t = t if isinstance(t, str) else t["name"]
    return f'{t.split("/")[-1]}::{m["name"]}'


def main(path, root):
    events = json.load(open(path))["recording"]["events"]
    roots = {}
    for e in events:
        if e["type"] == "jdk.Compilation":
            v = e["values"]
            if v["compileLevel"] == 4 and root in name(v["method"]):
                roots[v["compileId"]] = name(v["method"]) + (" (OSR)" if v.get("isOsr") else "")
    print(f"C2 compiles matching {root!r}: {len(roots)}")
    agg = collections.Counter()
    for e in events:
        if e["type"] != "jdk.CompilerInlining":
            continue
        v = e["values"]
        if v["compileId"] not in roots:
            continue
        callee = name(v["callee"])
        if "setTag" in callee or "Store::store" in callee:
            desc = v["callee"].get("descriptor", "")
            agg[(callee + desc.split(")")[0] + ")", "inlined" if v["succeeded"] else "NOT inlined", v["message"])] += 1
    print("\n== setTag / store decisions inside those compiles (count, callee, outcome, reason)")
    for (callee, outcome, msg), n in sorted(agg.items(), key=lambda x: (-x[1], x[0])):
        print(f"{n:4}  {callee:70} {outcome:12} {msg}")
    print("\n== standalone C2 compiles: code size (bytes), inlined bytecode (bytes), method")
    for e in events:
        if e["type"] != "jdk.Compilation":
            continue
        v = e["values"]
        n = name(v["method"])
        if v["compileLevel"] == 4 and ("setTag" in n or "IdStore::store" in n):
            print(f'{v.get("codeSize"):6} {v.get("inlinedBytes"):5}  {n}{v["method"]["descriptor"].split(")")[0]})')


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "TraceShapeBenchmark::newApi")
