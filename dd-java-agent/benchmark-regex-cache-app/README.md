# Regex cache benchmark app

A small Spring Boot 3 app whose endpoints each do a common regex anti-pattern on a ~200 char payload per request,
plus `run-bench.sh` to compare three configs:

| config | what runs |
|---|---|
| `noagent` | the app alone |
| `agent` | `-javaagent:dd-java-agent.jar`, `-Ddd.trace.experimental.regex.cache.enabled=false` |
| `agent-cache` | same as `agent`, with `-Ddd.trace.experimental.regex.cache.enabled=true` |

The question it answers: does agent + regex cache beat no agent on a regex-heavy workload, and what does the cache
cost where it can't help?

This is a standalone Gradle build. The root `settings.gradle` does not include it, so it can't break the repo build.

## Endpoints

| path | per-request work |
|---|---|
| `/noregex` | control: splits the payload on `,` with `indexOf`, no regex |
| `/split` | `payload.split(",\\s*")` |
| `/sanitize` | `payload.replaceAll("[^A-Za-z0-9]", "")` |
| `/email` | `value.matches(EMAIL_REGEX)` for each of the 10 field values (`static final` constant) |
| `/mask` | `payload.replaceFirst("(\\w+)@(\\w+)\\.com", "$1@***.com")` |
| `/compile` | `Pattern.compile("(\\w+)=(\\d+)")` inline, then a `find()` loop |
| `/dynamic` | `payload.split(sep + "\\s*")`: the regex is a new string on every request, so the cache never admits it. It shows the miss-path cost. |
| `/admin/stats` | process CPU time, GC, and the agent's `RegexCache` counters (read reflectively, `-1` without the agent) |

## Run

You need a JDK 17+ (set `BENCH_JAVA_HOME`, or on macOS it is found with `/usr/libexec/java_home -v 17+`) and an agent jar:

```shell
./gradlew :dd-java-agent:shadowJar      # from the repo root -> dd-java-agent/build/libs/dd-java-agent-*.jar
cd dd-java-agent/benchmark-regex-cache-app
./run-bench.sh -a ../build/libs/dd-java-agent-*-SNAPSHOT.jar
```

The script builds the app with `../../gradlew -p . bootJar loadgenJar` (pass `--skip-build` to reuse the jars). It then runs each config
in turn:

1. **Startup**: `StartupProbe` launches the app once unrecorded (to warm the OS file cache), then `--startup-runs` times
   (default 5). Each run is timed from process spawn to the first
   `200` from `/noregex`, then the app is killed. The report gives the median.
2. **Load**: the app starts once. For each endpoint, `LoadGen` runs a warmup (`--warmup`, default 15s) and then a fixed-duration
   measurement (`--duration`, default 30s), closed loop, with `--connections` keep-alive connections (default 8). It records
   throughput, p50/p90/p99/max latency, and the server's process CPU time over the measured window (from `/admin/stats`).
   CPU µs per request is the number to watch: it doesn't depend on how saturated the load generator is.

Options:

| option | meaning |
|---|---|
| `-a JAR` | dd-java-agent jar (required unless `--configs` is only `noagent`) |
| `-o DIR` | output dir (default `./results/<timestamp>`) |
| `--startup-runs N` | startup runs per config (default 5) |
| `--warmup S` | warmup seconds per endpoint (default 15) |
| `--duration S` | measured seconds per endpoint (default 30) |
| `--connections N` | concurrent keep-alive connections (default 8) |
| `--configs LIST` | comma-separated subset of `noagent,agent,agent-cache` (default: all three) |
| `--endpoints LIST` | default `noregex,split,sanitize,email,mask,compile,dynamic` |
| `--skip-build` | reuse the `build/libs` jars |
| `--heap SIZE` | `-Xms`/`-Xmx` for the app (default `512m`) |

Environment: `BENCH_JAVA_HOME` (JDK 17+ for the app and tools; defaults to `JAVA_HOME` if it is 17+, else
`/usr/libexec/java_home -v 17+` on macOS), `APP_PORT` (default 18080), `FAKE_AGENT_PORT` (default 18126).

Outputs in `results/<timestamp>/` (or `-o DIR`):

- `results.tsv`: raw rows
- `summary.json`: machine-readable summary
- `summary.md`: tables with deltas against `noagent`
- `logs/`: app logs and each config's exact command line

The full default run takes roughly 3 configs × (5 startups + 7 endpoints × 45s), so about 20 minutes.

Quick smoke run (numbers mean nothing):

```shell
./run-bench.sh -a ../build/libs/dd-java-agent-*-SNAPSHOT.jar --startup-runs 2 --warmup 1 --duration 2 --connections 2
```

There are no `wrk`/`hey`/`oha` dependencies. The load generator, startup probe, fake agent and report generator are plain JDK code in
`src/loadgen`, packaged as `build/libs/regex-bench-tools.jar`, and they always run in their own JVMs without the agent.

## How the "agent installed" baseline is pinned

`agent` and `agent-cache` get exactly the same flags except `dd.trace.experimental.regex.cache.enabled` (`false` vs `true`).
The goal is a realistic tracing setup without unrelated background noise:

- Tracing stays **on**. Spring MVC and Tomcat server spans are created, serialized and sent over HTTP to `FakeAgent`, a local stub on
  `FAKE_AGENT_PORT` (default 18126) that returns 200 for everything. That keeps the real trace-pipeline cost in the agent process
  without a real Datadog Agent competing for CPU. After each agent config, the script prints how many trace payloads the fake agent
  received and warns if the count is 0.
- Turned off: remote config, telemetry, profiling, AppSec, IAST, dynamic instrumentation, data streams, JMXFetch, health metrics,
  client-side stats, crash tracking, and startup logs. IAST must stay off anyway, because the regex cache is not applied when IAST is
  fully enabled.
- All three configs share `-Xms512m -Xmx512m -XX:+UseG1GC` (`--heap` changes the size).

## Read results with care

- The `RegexCache counters` table in `summary.md` shows whether the cache is active: hits should climb for `agent-cache`
  and stay at 0 for `agent`.
- Run on a quiet machine. On a laptop, plug in power and close other apps. On Linux, consider `taskset` to pin the app and the load
  generator to separate cores (wrap `java` in `BENCH_JAVA_HOME` with a script that does it).
- The load generator shares the box with the app. Throughput is limited by whichever side saturates first. CPU per request is more
  robust than raw req/s, and increasing `--connections` until throughput stops climbing tells you where the app saturates.
- Endpoints run in a fixed order in one JVM. JIT and GC state carry over from one endpoint to the next. The warmup absorbs most of
  this, but rerun a single endpoint (`--endpoints split`) if a number looks off.
