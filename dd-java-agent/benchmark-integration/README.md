# Datadog Java Agent Performance Tests
Integration level performance tests for the Datadog Java Agent.

## Perf Script Dependencies

`run-perf-test.sh` requires the following (available on homebrew or a linux package manager):

* bash (>=4.0)
* wrk
* nc

## Running a Test
1. Build the shadow jar or the distribution zip for the server you wish to test against.
2. Run the performance test script passing in the agent jars you wish to test.
3. (optional) Save test results csv and ponder the great mysteries of performance optimization.

### Example
#### Jetty
```
./gradlew dd-java-agent:benchmark-integration:jetty-perftest:shadowJar
# Compare a baseline (no agent) to the 0.18.0 and 0.19.0 releases.
/usr/local/bin/bash ./run-perf-test.sh jar jetty-perftest/build/libs/jetty-perftest-*-all.jar NoAgent ~/Downloads/dd-java-agent-0.18.0.jar ~/Downloads/dd-java-agent-0.19.0.jar
cp /tmp/perf_results.csv ~/somewhere_else/
```
#### Play
```
./gradlew :dd-java-agent:benchmark-integration:play-perftest:dist
# Compare a baseline (no agent) to the 0.18.0 and 0.19.0 releases.
/usr/local/bin/bash ./run-perf-test.sh play-zip play-perftest/build/distributions/main-*.zip NoAgent ~/Downloads/dd-java-agent-0.18.0.jar ~/Downloads/dd-java-agent-0.19.0.jar
cp /tmp/perf_results.csv ~/somewhere_else/
```

## PetClinic macro benchmark (self-serve)

`run-petclinic.sh` is the one-command version of the above against **Spring PetClinic** — a real
Spring MVC app that exercises a broad slice of the agent's instrumentation. It downloads and builds
PetClinic, runs it under load with the agent, captures a JFR per variant, and prints **throughput**
plus the **tracer-overhead split** (CPU + sampled allocation, bucketed foreground/background and
instrumentation/core). The goal is that any developer — not just perf specialists — can reproduce the
macro measurement that keeps micro benchmarks honest.

This is the fast, **local, dev-iteration** tool. It complements, and does not replace, the org's
automated benchmarking platform (the GitLab `apm-sdks-benchmarks-java-slo` track).

### Extra dependencies
On top of `wrk` and `nc`: `git`, a JDK (provides the `jfr` CLI), and `curl`. Maven is supplied by
PetClinic's own wrapper. First run needs network access (clone + build + optional release download).

### Running PetClinic without a class loader lock
Out of the box, PetClinic can't use more than about one core: every request's Thymeleaf links
(`@{/resources/...}`, `@{/webjars/...}`) go through Spring's resource chain, which looks each one up
on the class path, and the lookup isn't cached when it finds nothing. Every request thread then
queues on one lock: Spring Boot's nested-jar cache (`UrlJarFiles$Cache`) when run as a fat jar, or
the JDK's `URLClassPath` when extracted. Throughput stays flat as connections go up and only latency
grows, and that queueing hides tracer overhead.

So the harness runs PetClinic **extracted** (`fetch-petclinic.sh` runs `-Djarmode=tools extract` on
the fat jar) with **the resource chain off** (`SPRING_WEB_RESOURCES_CHAIN_ENABLED=false`, set by
`run-petclinic.sh`). Pages render the same, except asset links aren't rewritten to versioned URLs,
and wrk doesn't fetch assets.

NoAgent on `/` at 64 connections (M1 Max):

| PetClinic run as | req/s | p90 |
|---|---|---|
| fat jar | ~1,300 | 61 ms |
| extracted | ~4,200 | 23 ms |
| extracted, resource chain off | ~17,500 | 4.3 ms |

### Trace destination — what actually gets measured
By default, `run-petclinic.sh` starts a local **Agent stub** (`agent-stub/AgentStub.java`, a
single-file Java program) on `PETCLINIC_STUB_PORT` (default 8137) and points the agent variants at
it. The stub serves a real Agent's `/info` (so client-side stats, dropping unsampled traces and peer
tags are discovered as with a real Agent), accepts and discards payloads immediately, and counts what
it received. After each agent variant the harness checks the stub received traces, and **aborts** if
it got none.

Why not the local Datadog Agent: if it's slow, or rejecting payloads (an expired API key answers
every payload with 403), the tracer's writer backs up and holds traces in the heap. That changes the
GC behavior being measured, and nothing in the results shows it — it looks like a noisy or
unrepeatable benchmark. The stub takes that variable out.

`PETCLINIC_AGENT=real` uses the Agent on `TRACE_AGENT_PORT` (default 8126) instead. Then the harness
auto-detects it and picks the writer accordingly:
- **Agent reachable** (`DDAgentWriter`) — the *full pipeline*: span work on the request threads **and**
  the background serializer.
- **Agent unreachable** (`LoggingWriter`) — traces are discarded, so you measure the **agent-down
  regime**: foreground overhead only, no serializer.

The harness prints a `>> REGIME:` banner so the choice is never silent, and re-checks before every
variant. Set `EXPECT_AGENT=up` (or `down`) to make it **fail fast** if reality differs (the stub mode
sets `up`).

### Upstream trace headers
By default every request carries the headers a traced upstream service would send
(`wrk-upstream-headers.lua`: W3C `traceparent`/`tracestate`, Datadog headers and propagation tags,
`baggage`), so each request goes through extraction and continues a distributed trace, as most
services' requests do. `PETCLINIC_UPSTREAM_HEADERS=false` sends plain requests; `PERF_WRK_SCRIPT`
passes any other wrk Lua script.

### Usage
```
# NoAgent vs the current checkout:
./run-petclinic.sh

# A version sweep for the historic curve — each token is a jar path, `current` (builds this
# checkout's shadow jar), or a release version (downloaded from GitHub releases, else Maven Central):
./run-petclinic.sh 1.54.0 1.58.0 1.62.0 current
```
Artifacts (throughput CSV, per-variant JFRs and GC logs) are copied to
`build/petclinic-results/results-<ts>/`. Per endpoint, the CSV reports mean latency, p99 latency,
throughput (mean and stdev across repeats), young GCs per 1,000 requests, and KB allocated per
request, both inside the measured window.

**KB/req is the allocation number to compare.** It comes from the GC log (each GC's heap before,
minus the heap after the previous one), so it counts everything the process allocated, on every
thread, with no sampling. The tracer's allocation cost is a variant's KB/req minus NoAgent's.
Analyze any recording on its own with `./analyze-jfr.sh <recording.jfr>`.

#### Heap regime (ample vs tight)
Server heap is a fixed `Xms=Xmx` knob (default 256m) — the regime where allocation overhead turns
into throughput cost. Set one heap with `server_heap=` in the `.rc` (or `PERF_HEAP=`), or **sweep**
several by passing a list — the full variant sweep runs once per heap:
```
PETCLINIC_HEAPS="64m 256m 512m" ./run-petclinic.sh 1.54.0 1.58.0 current
```
Results land in per-heap subdirs (`heap-64m/`, …) plus a combined `throughput-grid.csv` (leading
`Heap` column). **Runtime is heaps × variants** — a heap list multiplies the wall clock, so size the
version list accordingly.

#### Runs (fresh JVMs, rotated order)
`PETCLINIC_RUNS=N` (default 1) runs the whole variant sweep N times per heap, each a fresh JVM per
variant, with the variant order (NoAgent included) rotated every run, so drift over a long sweep
spreads across variants instead of always landing on whichever runs first. Each run's artifacts go
in `heap-<h>/run-<n>/`, the combined grid gets a `Run` column, and with more than one run a
`median-grid.csv` of per-variant medians (throughput and KB/req per endpoint) is printed at the end:
```
PETCLINIC_RUNS=3 PETCLINIC_HEAPS="72m 256m" ./run-petclinic.sh current
```
Prefer runs over repeats for comparisons: repeats share one JVM, so they can't show JVM-to-JVM
(e.g. JIT) variation, which is often the larger part of the noise.

#### Repeats (noise vs. a single sample)
Throughput on a shared dev box is noisy -- a single wrk run per endpoint can swing several percent
run to run. Set `PETCLINIC_REPEATS=N` to warm up once per endpoint and then measure N back-to-back
windows (no server restart between them); the CSV reports the mean and stdev of throughput across
the repeats (`<endpoint> Throughput`, `<endpoint> Throughput Stdev`), so you can see the noise band
instead of a single point estimate:
```
PETCLINIC_REPEATS=5 ./run-petclinic.sh 1.58.0 1.62.0 current
```
Default is 1 repeat (no change in behavior/runtime from before this knob existed). Repeats multiply
runtime the same way heaps do -- factor them into the total when combined with a heap sweep.

PetClinic is pinned to a specific commit in `fetch-petclinic.sh` (bump deliberately); JFR is opt-in in
`run-perf-test.sh` via `PERF_JFR=1`, and endpoints/load live in `perf-test-petclinic-settings.rc`.

### Reading the results honestly
- **Allocation is the anchor; throughput is directional.** Allocation trends are stable across runs;
  throughput on a shared dev box is noisy and swings with the CPU-contention regime — read it as a
  direction, not a precise number.
- Allocation here is **sampled** by JFR (`jdk.ObjectAllocationSample`, throttled); the analyzer
  weights each sample by the bytes it stands for, so the splits (which thread, which package) are
  by estimated bytes, not by sample count, and the total is an estimate. That's sufficient for a
  macro trend.
- Young GCs and KB per request need JDK 9+ (unified GC logging); on JDK 8 those columns are empty.
- In the JFR split, a sample is owned by the first non-JDK frame from the leaf, so work the tracer
  does through JDK code (e.g. `HashMap.resize`) counts as tracer, while app code running inside an
  instrumented filter counts as app. Advice woven into an app method doing work inline, without
  calling a `datadog.*` method, still counts as app, so the tracer share is a lower bound; KB/req vs
  NoAgent is the total.
- Run on an otherwise-**quiet machine**, and respect the warmup (the harness warms each endpoint
  before measuring). Results depend on the machine's core count / heap regime.

### Known limitations (follow-ups)
- No containerization yet — no pinned cores/heap, so cross-machine comparisons are approximate.
- `run-perf-test.sh` is still lightly macOS-flavored (`/usr/bin/time`, `lsof`, `nc`); there is a basic
  Linux guard but not full cross-platform hardening.
