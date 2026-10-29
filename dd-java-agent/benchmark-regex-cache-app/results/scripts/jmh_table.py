import json, sys, collections

data = json.load(open(sys.argv[1]))
rows = collections.defaultdict(dict)
for b in data:
    name = b["benchmark"].rsplit(".", 1)[1]
    op, impl, kind = name.split("_")
    inp = b["params"]["input"]
    pm = b["primaryMetric"]
    alloc = b.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
    rows[(op, kind, inp)][impl] = (pm["score"], pm["scoreError"], alloc)

order = ["compile", "compileFlagsFind", "patternMatches", "matches", "replaceAll", "replaceFirst",
         "split", "splitLimit", "splitFastpath"]
out = ["| op | case | input | JDK ns/op | RegexOps ns/op | delta | JDK B/op | RegexOps B/op |",
       "|---|---|---|---:|---:|---:|---:|---:|"]
for kind in ["literal", "dynamic"]:
    for op in order:
        for inp in ["short", "long"]:
            r = rows.get((op, kind, inp))
            if not r or "jdk" not in r or "ops" not in r:
                continue
            j, o = r["jdk"], r["ops"]
            d = (o[0] - j[0]) / j[0] * 100
            label = "literal (hit)" if kind == "literal" else "dynamic (miss)"
            out.append(f"| {op} | {label} | {inp} | {j[0]:.1f} ± {j[1]:.1f} | {o[0]:.1f} ± {o[1]:.1f} | "
                       f"{d:+.1f}% | {j[2]:.0f} | {o[2]:.0f} |")
print("\n".join(out))
