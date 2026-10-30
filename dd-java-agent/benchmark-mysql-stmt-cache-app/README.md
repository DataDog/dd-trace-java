# MySQL statement cache benchmark

Benchmarks for the `DD_TRACE_EXPERIMENTAL_MYSQL_STMT_CACHE_ENABLED` POC. With the flag on, the agent turns on
Connector/J's client-side prepared statement cache (`cachePrepStmts=true`, `prepStmtCacheSize=250`,
`prepStmtCacheSqlLimit=2048`, the values HikariCP recommends) for connections that don't configure it. The
driver's default is `cachePrepStmts=false`, so every `prepareStatement` parses the SQL again on the client.
`useServerPrepStmts` is never touched, so the bytes sent to MySQL are the same either way.

The instrumentation lives in `dd-java-agent/instrumentation/jdbc/mysql-connector-j-8.0`. This directory is a
standalone Gradle build. The root `settings.gradle.kts` does not include it, so it can't break the repo build.

There are two benchmarks:

| | what it measures | agent? |
|---|---|---|
| microbenchmark (`--mode micro`) | Connector/J alone: the library-level ceiling of what the cache can save | no |
| demo app (`--mode app`) | Spring Boot + HikariCP app under `noagent`, `agent`, `agent-stmtcache` | yes |

## Read this before the numbers

- **Client CPU per request (or per op) is the primary metric.** MySQL runs on the same box over loopback.
  Loopback hides most of the network latency a real deployment has, so latency and throughput look more
  DB-bound than in production in some ways and less in others. At the same time, the client's share of the
  total CPU looks bigger than it would next to a remote database. Use CPU us/request, not req/s, to judge
  the feature. Don't read a loopback latency change as a production latency change.
- **On macOS with Colima**, the container is reached through the VM's port forwarding. A round trip costs
  roughly 300 to 400 us, versus tens of us with native Docker on Linux. That makes `/db` latency and
  `prepare-exec` wall time mostly network. The client CPU numbers are still valid, but run on Linux if you
  want latency numbers that mean something.
- The cache only removes client-side SQL parsing. It does nothing for result-set decoding, network I/O,
  pooling, or the server's own parse/plan work.
- Per-connection memory: up to 250 parsed statements (SQL text plus parse info) are kept per pooled
  connection. This benchmark doesn't measure that memory.

## Exact commands for the measurement step

Run alone on a quiet machine: no other Gradle builds or benchmarks. On a laptop, plug in power and close
other apps. You need Docker (`colima start` on macOS) and a JDK 17+.

```shell
cd "$(git rev-parse --show-toplevel)"

# 1. Agent jar from this branch (skip if dd-java-agent/build/libs already has one from this commit)
./gradlew :dd-java-agent:shadowJar
AGENT_JAR="$(ls dd-java-agent/build/libs/dd-java-agent-*-SNAPSHOT.jar | head -1)"

# 2. Both benchmarks with the default settings, about 10 minutes
cd dd-java-agent/benchmark-mysql-stmt-cache-app
./run-bench.sh -a "../../$AGENT_JAR" --mode all -o results/$(date +%Y%m%d)-pass1

# 3. A second pass in reverse config order, to expose order effects
./run-bench.sh -a "../../$AGENT_JAR" --mode all --configs agent-stmtcache,agent,noagent \
  -o results/$(date +%Y%m%d)-pass2-reversed
```

Optional, for more stable numbers: `--duration 60 --startup-runs 10 --micro-rounds 10`. To see where the app
saturates, raise `--connections` (8, 16, 32) until req/s stops climbing.

Durations with the defaults: the microbenchmark runs 6 rounds (1 warmup) × 8 cells × 3 s, about 2.5 minutes.
The app runs 3 configs × (6 startups + 2 endpoints × 45 s), about 6 minutes. Seeding takes about 15 s.

Correctness tests (also need Docker). On macOS with Colima, Testcontainers needs the socket spelled out:

```shell
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true
./gradlew :dd-java-agent:instrumentation:jdbc:mysql-connector-j-8.0:test \
  :dd-java-agent:instrumentation:jdbc:mysql-connector-j-8.0:forkedTest \
  :dd-java-agent:instrumentation:jdbc:mysql-connector-j-8.0:latestDepTest \
  :dd-java-agent:instrumentation:jdbc:mysql-connector-j-8.0:latestDepForkedTest
```

The differential test writes both transcripts to
`dd-java-agent/instrumentation/jdbc/mysql-connector-j-8.0/build/stmt-cache-corpus/{off,on}.txt`.

## What `run-bench.sh` does

1. Builds the app, the tools and the microbenchmark with `../../gradlew -p . bootJar loadgenJar microJar`.
   Pass `--skip-build` to reuse the jars.
2. Starts `mysql:8.0` as container `stmtcache-bench-mysql` on `127.0.0.1:13306`, with its datadir on tmpfs.
   It reuses the container if one is running, and removes it at the end unless you pass `--keep-mysql`.
   With `--no-container` it uses whatever listens on `--mysql-port` (database, user and password are all
   `bench`).
3. Seeds the schema without the agent: 10k items, 2k customers, 40k orders, from a fixed random seed.
4. `--mode micro|all`: runs the microbenchmark (below).
5. `--mode app|all`: for each config, in order:
   - **Startup**: `StartupProbe` launches the app once unrecorded (to warm the OS file cache), then
     `--startup-runs` times (default 5). Each run is timed from process spawn to the first `200` from
     `/nodb`. The app opens the Hikari pool and runs one query during startup, so the time includes
     connecting to MySQL, which is where the advice runs.
   - **Load**: one app process. For each endpoint, `LoadGen` runs a warmup (`--warmup`, default 15 s) and a
     fixed-duration measurement (`--duration`, default 30 s), closed loop, with `--connections` keep-alive
     connections (default 8). It records req/s, p50/p90/p99/max latency, and the server's process CPU time
     over the measured window (from `/admin/stats`).
   - After the load, it prints the effective `cachePrepStmts` of a pooled connection and how many parsed
     statements its cache holds. Expect `0 / -1` for `noagent` and `agent`, and `1 / >0` for
     `agent-stmtcache`. If not, the run did not test the feature.

### Configs

| config | JVM flags |
|---|---|
| `noagent` | the app alone |
| `agent` | `-javaagent:dd-java-agent.jar`, `-Ddd.trace.experimental.mysql.stmt.cache.enabled=false` |
| `agent-stmtcache` | same as `agent`, with `-Ddd.trace.experimental.mysql.stmt.cache.enabled=true` |

`agent` and `agent-stmtcache` differ only in that flag. Tracing is on: Spring MVC, Tomcat and JDBC spans
are created, serialized and sent to `FakeAgent`, a local stub on `FAKE_AGENT_PORT` (default 18126) that
returns 200 for everything. Remote config, telemetry, profiling, AppSec, IAST, dynamic instrumentation,
data streams, JMXFetch, health metrics, client-side stats, crash tracking and startup logs are off. All
configs use `-Xms512m -Xmx512m -XX:+UseG1GC` (`--heap` changes the size).

### Endpoints

| path | per-request work |
|---|---|
| `/nodb` | control: no database work |
| `/db` | one pooled connection, 5 prepared queries with fresh `prepareStatement` each time (like JdbcTemplate and Hibernate), written ORM-style with explicit column lists: item by PK, customer by unique email, last 10 orders of a customer joined to item, order counts by status, items in a category and price range. All indexed. |
| `/admin/stats` | process CPU, GC, effective `cachePrepStmts` and cache size on one pooled connection |

### Outputs

In `results/<timestamp>/`, or the directory passed with `-o`:

- `summary.md`: startup and load tables, with deltas against the first config, plus the effective cache table
- `summary.json` and `results.tsv`: the same data, machine-readable and raw
- `micro.md` and `micro.tsv`: microbenchmark medians and per-round raw rows
- `logs/`: app, seed and micro logs, plus each config's exact command line

## Microbenchmark

`StmtCacheMicroBench` (in `src/micro`) uses plain Connector/J 8.4.0: no Spring, no pool, no agent. It holds
one connection per variant on one thread:

- `off`: `cachePrepStmts=false`, the driver default
- `on`: exactly what the agent adds: `cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048`

There are two workloads, each run for a short PK lookup (`simple`) and a ~330 character ORM-style join
(`orm`):

- `prepare-exec`: `prepareStatement`, set params, `executeQuery`, read the row, close. This is the realistic
  loop, network included.
- `prepare-only`: `prepareStatement`, set params, close. A client-side prepare sends nothing to the server,
  so this isolates the parse cost the cache removes. It is the ceiling.

Client CPU per op is the CPU time of the benchmark thread (`ThreadMXBean`). It excludes MySQL and the GC
threads. Allocation per op is reported too. Each round runs every cell for `--micro-seconds`, the variant
order alternates between rounds, and the report shows the median of the rounds.

Run it on its own:

```shell
./run-bench.sh --mode micro --micro-rounds 10 --micro-seconds 5
# or against an existing MySQL
java -cp build/libs/stmtcache-micro.jar com.example.stmtcachebench.micro.StmtCacheMicroBench \
  --url jdbc:mysql://127.0.0.1:13306/bench --user bench --password bench --rounds 5 --seconds 3 --out results/micro
```

## Smoke run (numbers mean nothing)

```shell
./run-bench.sh -a ../build/libs/dd-java-agent-*-SNAPSHOT.jar --mode all \
  --startup-runs 2 --warmup 1 --duration 2 --connections 2 --micro-rounds 1 --micro-seconds 1
```

The tools (load generator, startup probe, fake agent, report) are copied from the regex cache POC
(`benchmark-regex-cache-app`). They are plain JDK code in `src/loadgen`, packaged as
`build/libs/stmtcache-bench-tools.jar`, and they always run in their own JVMs without the agent.
