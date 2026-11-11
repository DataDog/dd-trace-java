# kafka-dsm

Kafka produce/consume workload used to measure how much Data Streams Monitoring (DSM) adds on top
of APM. `Workload.java` has no tracer-specific code; `run.sh` selects the scenario. It is the Java
counterpart of dd-trace-py's `benchmarks/kafka_dsm`.

Each iteration, on 1 thread (`NUM_THREADS`): create a fresh single-partition topic and consumer
group; (untimed) build a `kafka-clients` producer and consumer, fetch the topic metadata and join
the group; (timed) produce 1000 unkeyed messages (fixed 1024-byte payload, headers `key0..key4` ->
`value0..value4`) + `flush()`; (timed) consume them with a `commitSync` every `COMMIT_EVERY`
messages and after the last one; (untimed) close the clients, then sample process RSS. Duration per
iteration = produce + consume. All iterations run in one JVM; see the `Workload.java` Javadoc for
the JIT/GC metrics.

## Run

```
./run.sh <baseline|apm-only|apm+dsm> [slug]
```

- `baseline`: no `-javaagent`.
- `apm-only`: `-javaagent:$DD_TRACE_JAVA_AGENT_JAR`, `DD_DATA_STREAMS_ENABLED=false`.
- `apm+dsm`: `-javaagent:$DD_TRACE_JAVA_AGENT_JAR`, `DD_DATA_STREAMS_ENABLED=true`. File/topic slug:
  `apm-plus-dsm`.

Build the agent with `./gradlew :dd-java-agent:shadowJar` (the jar lands in
`dd-java-agent/build/libs/`). Requires JDK 11+ (`javac`), Kafka on `localhost:9092` and an endpoint
that accepts the tracer's payloads, such as a Datadog Agent or a mock agent (`DD_TRACE_AGENT_URL`,
default `http://127.0.0.1:18127`). A dead port adds connection-error overhead to the tracer
scenarios. `kafka-clients` 3.9.0 and slf4j 1.7.36 come from `/opt/kafka-clients` (benchmarking-platform
image) or are downloaded once to `build/kafka-clients/`.

## Environment

| Variable | Default | |
|---|---|---|
| `DD_TRACE_JAVA_AGENT_JAR` | unset | agent jar for the tracer scenarios (required for them) |
| `COMMIT_EVERY` | 100 | sync commit every N messages; integer >= 1 |
| `NUM_THREADS` | 1 | worker threads, one topic each |
| `WARMUP` / `COUNT` | 100 / 25 | discarded / timed iterations (`WARMUP` >= 0, `COUNT` >= 1); the tracer keeps C2 busy well past 5 iterations |
| `EXPERIMENT_NAME` | `<scenario-slug>` | `name` in the result JSON |
| `JAVA_HEAP` | `1g` | `-Xms` and `-Xmx`, the same for every scenario |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `KAFKA_TOPIC` | `benchmark-topic-java-<scenario-slug>-<slug>` | base name; a per-process run id, the iteration and the thread are appended |
| `KAFKA_CLIENTS_DIR` | see above | directory with `kafka-clients.jar`, `slf4j-api.jar`, `slf4j-nop.jar` |
| `OUT_DIR` | `build/out/` in this folder | default directory for the result |
| `TIMEIT_OUTPUT` | `$OUT_DIR/java-<slug>.<scenario-slug>.json` | result JSON (see below) |

CI: the `dsm-throughput` job (`.gitlab/benchmarks/dsm-throughput.yml`) runs this on
benchmarking-platform branch `java/data-streams-monitoring` with the pipeline's agent jar
(automatic on `master`, manual elsewhere, never blocking).

## Output

`TIMEIT_OUTPUT` holds one result per scenario, over the timed iterations:

```
[{"median": <duration ms>, "metrics": {
  "process.internal_duration_ms.median": <ms>, "process.internal_duration_ms.std_err": <ms>,
  "process.rss_bytes.median": <bytes>, "phase.produce_ms.median": <ms>,
  "phase.consume_ms.median": <ms>, "config.commit_every": <int>, ...},
  "name": <EXPERIMENT_NAME>, "warmUpCount": <WARMUP>, "count": <COUNT>,
  "data": [{"status": "Passed", "metrics": {"process.internal_duration_ms": <ms>,
    "process.corrected_duration_ms": <iteration wall ms>, "runtime.dotnet.mem.committed": <RSS bytes>}},
    ...one per timed iteration]}]
```

The extra `metrics` keys (`process.iteration_wall_ms.median`, `jvm.*`, `warmup.iterations`) are
context only. `data` feeds the benchmarking-platform's bp-analyzer TimeItSharp converter, which
reads TimeItSharp's metric names (it maps `runtime.dotnet.mem.committed` to RSS for any runtime).
RSS is the current RSS from `/proc/self/status` (`VmRSS`), so the workload runs on Linux only.
