# kafka-dsm-perftest

Standalone (Maven) Kafka produce/consume benchmark used to measure the overhead of APM and of
Data Streams Monitoring (DSM) in the Java tracer built from **this** repo/worktree. It is not part
of the Gradle build.

Client: `kafka-clients` 3.9.0. Runtime: Temurin 25 (`run.sh` selects it with `java_home -v 25`).

## Build the tracer, then run

```shell
./gradlew :dd-java-agent:shadowJar            # from the repo root -> dd-java-agent/build/libs/dd-java-agent-*.jar
dd-java-agent/benchmark-integration/kafka-dsm-perftest/run.sh <baseline|apm-only|apm+dsm> [slug]
```

`run.sh` resolves the agent jar relative to the repo root of the worktree it lives in
(override with `DD_JAVA_AGENT=/path/to/dd-java-agent.jar`) and builds the app with `mvn package` on
first use. Needs Kafka at `localhost:9092` (empty broker is not required, a fresh topic is created
per iteration) and, for the tracer scenarios, a mock/real Datadog agent at `DD_TRACE_AGENT_URL`
(default `http://127.0.0.1:18127`).

Scenarios: `baseline` (no `-javaagent`), `apm-only` (`DD_DATA_STREAMS_ENABLED=false`),
`apm+dsm` (`DD_DATA_STREAMS_ENABLED=true`).

## Iteration

Per iteration: create a fresh single-partition topic; untimed setup (producer + consumer, wait for
partition assignment); **timed** produce of 1000 messages with 5 headers + `flush()`; **timed**
consume of 1000 messages with `commitSync()` every `COMMIT_EVERY` and after the last; untimed
teardown. `WARMUP` iterations are discarded, `COUNT` are timed.

## Configuration (env)

| Var | Default |
|---|---|
| `COMMIT_EVERY` | 100 |
| `WARMUP` / `COUNT` | 5 / 25 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `KAFKA_TOPIC` | `benchmark-topic-java-<scenario>-<slug>` (`+` → `plus`; run/iteration suffix appended) |
| `TIMEIT_OUTPUT` | `results.java.<scenario>.json` (in this folder) |
| `HEAP_MB` | 512 |
| `PROFILE` | 0; `1` records JFR |
| `PROFILE_OUTPUT` | `profile.java.<scenario>.jfr` (in this folder) |

Output JSON: `{"runs":[{"produce_ms":..,"consume_ms":..},..],"max_rss_mb":..,"final_rss_mb":..,
"heap_used_after_gc_mb":..,"nmt_committed_mb":..,"commit_every":..}`; `max_rss_mb` is the max RSS
(via `ps`) sampled after every timed iteration. All scenarios run with a fixed heap
(`-Xms=-Xmx=$HEAP_MB`, default 512m, no AlwaysPreTouch) and `-XX:NativeMemoryTracking=summary`;
`heap_used_after_gc_mb` is measured after an explicit GC and `nmt_committed_mb` is the NMT total
committed (incl. heap) at the end of the run. Memory overhead is computed from `nmt_committed_mb`.

## Profiling

`PROFILE=1` starts a JFR `Recording` right before the timed produce and stops it right after the
timed consume of every timed iteration (setup, warmup and teardown are not profiled; the
per-iteration chunks are concatenated into `PROFILE_OUTPUT`). `jfr_top.py` then writes
`<PROFILE_OUTPUT minus .jfr>.top.txt` with top self/total methods from `jdk.ExecutionSample`.
Timings of profiled runs are inflated: measure with `PROFILE=0`.
