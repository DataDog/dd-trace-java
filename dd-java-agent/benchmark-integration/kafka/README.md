# Kafka macro benchmark

Measures the tracer's per-message cost on Kafka clients by running Kafka's own perf tools
(`kafka-consumer-perf-test`, `kafka-producer-perf-test`) under the agent.

The tools use the standard `kafka-clients` library, so the agent's Kafka instrumentation runs on the
real client paths; no application code is needed.

## Why this workload

- **The consumer does almost no work per record** (about 0.7 µs to fetch and count), so the tracer's
  cost per record is a large share of the work. That makes it a sensitive, low-noise probe:
  run-to-run spread is about ±2–3%.
- **Every record carries real trace headers:** the topic is filled by an agent-instrumented producer,
  so each consumed record goes through extraction (Datadog, W3C `traceparent` / `tracestate`) and
  gets a consume span. This exercises propagation on every message.
- **Report the absolute cost (µs per record), not the relative throughput drop.** The relative drop
  is large here only because the consumer does so little; the absolute cost carries over to consumers
  that do real work per message.

## Usage

Requires JDK 17+ (`BENCH_JAVA_HOME`, `JAVA_17_HOME` or `JAVA_HOME`), `curl`, `shasum` and `python3`.
Set `TRACE_AGENT_PORT` if your local Datadog Agent isn't on 8126.

```shell
./fetch-kafka.sh                      # Apache Kafka 3.9.1, SHA-512 verified, unpacked under build/
./broker.sh start                     # single-node KRaft broker, no agent
./prefill.sh path/to/dd-java-agent.jar        # 10M records with trace headers
./preflight.sh path/to/dd-java-agent.jar      # headers injected? consumer continues the trace?

./run-consumer.sh NoAgent master=path/to/master.jar candidate=path/to/candidate.jar
./run-producer.sh NoAgent master=path/to/master.jar candidate=path/to/candidate.jar

./broker.sh stop
```

Variants are `NoAgent` or `label=path/to/agent.jar`. Each run is a fresh JVM, and the variant order
rotates every rep.

**Environment knobs:**

| variable | default | effect |
|---|---|---|
| `HEAPS` | `256m 128m 64m` | consumer heap sweep |
| `REPS` | `5` | fresh-JVM runs per variant |
| `MESSAGES` | `10000000` | records consumed per run |
| `RATE` / `RECORDS` / `HEAP` | `100000` / `3000000` / `256m` | producer: fixed offered rate, run length, heap |
| `DSM` | `false` | Data Streams on/off for all agent variants |
| `AGENT_EXTRA_OPTS` | — | extra JVM/agent options for agent variants |
| `PREFILL_RECORDS` / `RECORD_SIZE` | `10000000` / `100` | topic size and record size |

Results go to `build/results/<consumer|producer>-<timestamp>/`: a `results.csv`, per-run output, and
GC logs.

`analyze.py results.csv [baseline]` prints, per heap and variant:
- median throughput, with every run shown so bimodality is visible;
- tracer µs per record vs NoAgent;
- change vs a baseline variant;
- young GCs per million records.

## Example results (2026-10-25, one laptop, JDK 17, 10M records, 5 reps)

**Consumer, fetch records/s (median):**

| heap | NoAgent | master | dense-store stack | trace-segment stack | both stacks |
|---|---|---|---|---|---|
| 256m | 1447k | 288k | 301k (+4.6%) | 326k (+13.2%) | 331k (+15.0%) |
| 128m | 1437k | 275k | 288k (+4.7%) | 308k (+12.2%) | 309k (+12.6%) |
| 64m | 1412k | 229k | 239k (+4.2%) | 256k (+11.7%) | 260k (+13.6%) |

- Runs for different builds don't overlap; at 256m, master spans 275–294k and both stacks 325–337k.
- The tracer adds about 2.8 µs per record on master.
- Skipping Data Streams work when DSM is off adds a further ~6.6%.
- The producer at a fixed 100k records/s showed no difference between agent builds (p99 4–5 ms).

**Caveats:** single machine; laptop CPU frequency and core placement vary between JVM starts. Use
dedicated hosts for gating decisions.
