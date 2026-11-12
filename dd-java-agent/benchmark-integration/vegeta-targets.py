#!/usr/bin/env python3
"""Write vegeta JSON targets for one URL, one request per line.

With --upstream-headers each target carries the same upstream trace headers as
wrk-upstream-headers.lua (W3C traceparent/tracestate, Datadog headers and propagation tags, baggage)
with its own random IDs, so each request continues a different distributed trace. vegeta cycles
through the targets, so --count only needs to be large enough that traces don't visibly repeat.

    vegeta-targets.py URL [--count N] [--upstream-headers] > targets.json
"""
import argparse
import json
import random
import sys


def id64():
    # below 2^53 and never zero, as in the Lua script, so the decimal form stays exact there too
    a, b, c = random.randint(0, 31), random.randint(0, 0xFFFFFF), random.randint(1, 0xFFFFFF)
    return "%04x%06x%06x" % (a, b, c), str(a * 2**48 + b * 2**24 + c)


def hex64():
    return "%016x" % random.getrandbits(64)


def upstream_headers(sampled_fraction=1.0):
    """Headers from an upstream service that kept the trace with probability sampled_fraction."""
    hi = hex64()
    lo_hex, lo_dec = id64()
    span_hex, span_dec = id64()
    keep = random.random() < sampled_fraction
    if keep:
        tracestate = "dd=s:1;o:rum;p:%s;t.dm:-4;t.tid:%s,othervendor=t61rcWkgMzE" % (span_hex, hi)
        priority, dd_tags = "1", "_dd.p.dm=-4,_dd.p.tid=%s" % hi
    else:
        tracestate = "dd=s:0;o:rum;p:%s;t.tid:%s,othervendor=t61rcWkgMzE" % (span_hex, hi)
        priority, dd_tags = "0", "_dd.p.tid=%s" % hi
    return {
        "traceparent": ["00-%s%s-%s-%s" % (hi, lo_hex, span_hex, "01" if keep else "00")],
        "tracestate": [tracestate],
        "x-datadog-trace-id": [lo_dec],
        "x-datadog-parent-id": [span_dec],
        "x-datadog-sampling-priority": [priority],
        "x-datadog-origin": ["rum"],
        "x-datadog-tags": [dd_tags],
        "baggage": ["user.id=4417,session.id=a9f3c2,tenant=acme"],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("url")
    parser.add_argument("--count", type=int, default=20000)
    parser.add_argument("--upstream-headers", action="store_true")
    parser.add_argument("--sampled-fraction", type=float, default=1.0,
                        help="share of requests whose upstream decision is keep (default 1)")
    args = parser.parse_args()
    out = sys.stdout
    for _ in range(args.count):
        target = {"method": "GET", "url": args.url}
        if args.upstream_headers:
            target["header"] = upstream_headers(args.sampled_fraction)
        out.write(json.dumps(target, separators=(",", ":")))
        out.write("\n")


if __name__ == "__main__":
    main()
