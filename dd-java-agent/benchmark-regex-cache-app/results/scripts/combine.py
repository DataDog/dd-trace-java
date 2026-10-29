import sys, statistics as st, collections

passes = sys.argv[1:]
load = collections.defaultdict(list)   # (endpoint, config) -> list of (rps, p50, p99, cpu)
startup = collections.defaultdict(list)
for i, path in enumerate(passes):
    per_pass_startup = collections.defaultdict(list)
    for line in open(path):
        f = line.rstrip("\n").split("\t")
        if f[0] == "startup":
            per_pass_startup[f[1]].append(float(f[3]))
        elif f[0] == "load":
            load[(f[2], f[1])].append((float(f[6]), float(f[7]), float(f[9]), float(f[12]), int(f[4])))
    for c, v in per_pass_startup.items():
        startup[c].append(st.median(v))

configs = ["noagent", "agent", "agent-cache"]
out = ["## Startup median (ms), per pass", "", "| config | pass 1 | pass 2 | mean | vs noagent |", "|---|---:|---:|---:|---:|"]
base = st.mean(startup["noagent"])
for c in configs:
    v = startup[c]
    m = st.mean(v)
    out.append(f"| {c} | {v[0]:.0f} | {v[1]:.0f} | {m:.0f} | {m - base:+.0f} ms ({(m - base) / base * 100:+.0f}%) |")

out += ["", "## Load, per pass (p1 / p2) and mean; deltas use the mean vs noagent mean", "",
        "| endpoint | config | req/s p1 / p2 | req/s vs noagent | p50 us p1 / p2 | p99 us p1 / p2 | CPU us/req p1 / p2 | CPU/req vs noagent | errors |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|"]
endpoints = []
for (e, c) in load:
    if e not in endpoints:
        endpoints.append(e)
for e in endpoints:
    b = load[(e, "noagent")]
    brps = st.mean(x[0] for x in b)
    bcpu = st.mean(x[3] for x in b)
    for c in configs:
        r = load[(e, c)]
        rps = st.mean(x[0] for x in r)
        cpu = st.mean(x[3] for x in r)
        d_rps = "-" if c == "noagent" else f"{(rps - brps) / brps * 100:+.1f}%"
        d_cpu = "-" if c == "noagent" else f"{(cpu - bcpu) / bcpu * 100:+.1f}%"
        out.append(f"| {e} | {c} | {r[0][0]:.0f} / {r[1][0]:.0f} | {d_rps} | {r[0][1]:.1f} / {r[1][1]:.1f} | "
                   f"{r[0][2]:.1f} / {r[1][2]:.1f} | {r[0][3]:.2f} / {r[1][3]:.2f} | {d_cpu} | {sum(x[4] for x in r)} |")
print("\n".join(out))
