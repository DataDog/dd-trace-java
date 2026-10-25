#!/usr/bin/env python3
"""Summarizes a run-consumer.sh results.csv.

Per heap and variant: median fetch throughput, the individual runs (so bimodality is visible), the
tracer's cost per record relative to NoAgent in the same session, the change vs a baseline variant,
and young GCs per million records.

Tracer cost per record = 1 / rate(variant) - 1 / rate(noagent). Unlike the relative throughput drop,
which depends on how little work the consumer itself does, this absolute cost carries over to
consumers that do real work per message.

    python3 analyze.py results.csv [baseline-label]   (baseline defaults to the first agent variant)
"""
import csv
import statistics
import sys
from collections import defaultdict


def main():
    path = sys.argv[1]
    rows = [r for r in csv.DictReader(open(path)) if r["fetch_msgs_per_sec"]]
    rates = defaultdict(list)
    gcs = defaultdict(list)
    order = []
    for r in rows:
        key = (r["heap"], r["variant"])
        rates[key].append(float(r["fetch_msgs_per_sec"]))
        gcs[key].append(1e6 * int(r["young_gcs"]) / max(1, int(r["msgs"])))
        if r["variant"] not in order:
            order.append(r["variant"])
    agents = [v for v in order if v != "noagent"]
    baseline = sys.argv[2] if len(sys.argv) > 2 else (agents[0] if agents else None)
    heaps = sorted({h for h, _ in rates}, key=lambda h: -int(h.rstrip("mg")))
    for heap in heaps:
        print(f"\n== heap {heap}")
        noagent = rates.get((heap, "noagent"))
        base = rates.get((heap, baseline))
        print(f"  {'variant':16} {'median msgs/s':>14} {'tracer us/rec':>14} {'vs ' + str(baseline):>14} {'GCs/1M':>8}  runs")
        for v in order:
            xs = rates.get((heap, v))
            if not xs:
                continue
            med = statistics.median(xs)
            cost = f"{1e6 / med - 1e6 / statistics.median(noagent):.2f}" if noagent and v != "noagent" else "-"
            delta = f"{100 * (med / statistics.median(base) - 1):+.1f}%" if base and v != "noagent" else "-"
            gc = statistics.median(gcs[(heap, v)])
            runs = "/".join(f"{x / 1000:.0f}k" for x in sorted(xs))
            print(f"  {v:16} {med:14.0f} {cost:>14} {delta:>14} {gc:8.0f}  {runs}")


if __name__ == "__main__":
    main()
