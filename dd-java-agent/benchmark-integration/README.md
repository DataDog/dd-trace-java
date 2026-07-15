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

### Trace destination — what actually gets measured
The harness auto-detects a Datadog Agent on `localhost:8126` and picks the writer accordingly. This
is a deliberate knob — it lets one tool measure two regimes:
- **Agent reachable** (`DDAgentWriter`) — the *full pipeline*: span work on the request threads **and**
  the background serializer (`TraceMapperV0_4`, msgpack, cache recalibration). Run your **local
  Datadog Agent** on `:8126` for this; the analyzer's `background/*` buckets then reflect real
  serialization cost.
- **Agent unreachable** (`LoggingWriter`) — traces are discarded, so you measure the **agent-down
  regime**: foreground overhead only, no serializer. Useful on its own for understanding behavior
  when the Agent is missing.

Pick intentionally based on what you want to see, and keep it consistent across a sweep. The harness
prints a `>> REGIME:` banner so the choice is never silent, and re-checks before every variant. Set
`EXPECT_AGENT=up` (or `down`) to make it **fail fast** if reality differs — e.g. a full-pipeline sweep
aborts immediately if the Agent isn't up, and aborts mid-sweep if it dies, instead of quietly
degrading to `LoggingWriter`. Default is `any` (auto-detect, no assertion).

### Usage
```
# NoAgent vs the current checkout:
./run-petclinic.sh

# A version sweep for the historic curve — each token is a jar path, `current`
# (builds this checkout's shadow jar), or a release version (downloaded from Maven Central):
./run-petclinic.sh 1.54.0 1.58.0 1.62.0 current
```
Artifacts (throughput CSV + per-variant JFRs) are copied to `build/petclinic-results/results-<ts>/`.
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

PetClinic is pinned to a specific commit in `fetch-petclinic.sh` (bump deliberately); JFR is opt-in in
`run-perf-test.sh` via `PERF_JFR=1`, and endpoints/load live in `perf-test-petclinic-settings.rc`.

### Reading the results honestly
- **Allocation is the anchor; throughput is directional.** Allocation trends are stable across runs;
  throughput on a shared dev box is noisy and swings with the CPU-contention regime — read it as a
  direction, not a precise number.
- Allocation here is **TLAB-sampled** (via JFR) — authoritative for *ratios* (which thread, which
  package), with only an *estimated* total. That's sufficient for a macro trend.
- Run on an otherwise-**quiet machine**, and respect the warmup (the harness warms each endpoint
  before measuring). Results depend on the machine's core count / heap regime.

### Known limitations (follow-ups)
- No containerization yet — no pinned cores/heap, so cross-machine comparisons are approximate.
- `run-perf-test.sh` is still lightly macOS-flavored (`/usr/bin/time`, `lsof`, `nc`); there is a basic
  Linux guard but not full cross-platform hardening.
