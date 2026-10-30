# Jackson Blackbird benchmark app

Measures the POC on branch `brian.marks/poc-jackson-blackbird`: with
`DD_TRACE_EXPERIMENTAL_JACKSON_BLACKBIRD_ENABLED=true`, the agent registers Jackson's Blackbird module
on the `ObjectMapper`s an application creates.

This directory holds two harnesses:

1. A JMH microbenchmark that measures the library-level ceiling without the agent: Jackson with
   and without Blackbird.
2. A Spring Boot 3 demo app plus `run-bench.sh`, which compare `noagent`, `agent` and
   `agent-blackbird` on JSON-heavy endpoints.

This is a standalone Gradle build (`../../gradlew -p . bootJar loadgenJar`). The root
`settings.gradle.kts` does not include it, so it can't break the repo build. The structure (LoadGen, StartupProbe, FakeAgent, Report, results format) is copied from
the regex-cache POC benchmark.

## How the feature works (what is being measured)

- The agent puts exit advice on the `ObjectMapper(JsonFactory, DefaultSerializerProvider,
  DefaultDeserializationContext)` constructor. Every non-copy constructor delegates to it, including
  `JsonMapper` and `JsonMapper.builder()`. Spring's `Jackson2ObjectMapperBuilder` calls
  `new ObjectMapper(...)`, so it goes through this constructor too.
- The advice registers `new BlackbirdModule()` only when all of these hold:
  - the flag is on
  - the JVM is 11+
  - the class loader can load `com.fasterxml.jackson.module.blackbird.BlackbirdModule` (the app has to
    bring the dependency)
  - the instance is exactly `ObjectMapper` or `JsonMapper`. Subclasses such as XmlMapper, YAMLMapper
    and app subclasses are skipped.
  - no Blackbird or Afterburner module is registered yet
  - fewer than 64 mappers in that class loader already got it
- Blackbird is registered before any module or configuration the app adds afterwards, so modifiers the
  app adds later run first. Copies made with `copy()` keep it.
- The cap exists because of this: Blackbird generates accessor classes for each mapper and bean type.
  The first smoke run had no cap, and the per-request-mapper endpoint loaded about 131k classes in 2 s
  at about 5x the CPU per request.

## 1. Microbenchmark (no agent): library ceiling

`src/jmh/java/com/example/jsonbench/jmh/JacksonBlackbirdBenchmark.java`:

- `serialize` / `deserialize` for each `mapper` in {`plain`, `blackbird`} and each `shape` in
  {`small` (~10 properties), `medium` (nested order, ~50 properties), `list100` (100 small DTOs)}.
  The setup fails if plain and Blackbird produce different bytes.
- `FirstUse.newMapperFirstUse`: create a mapper, then serialize and deserialize a medium DTO once.
  This is the per-mapper warmup cost.

Run it from this directory. It takes about 6 minutes with the defaults: 2 forks, 5x1 s warmup and 5x1 s
measurement, gc profiler.

```shell
cd dd-java-agent/benchmark-jackson-blackbird-app
mkdir -p results/$(date +%Y%m%d)-jmh
../../gradlew -p . jmh \
  -PjmhResults=$PWD/results/$(date +%Y%m%d)-jmh/jmh-result.json \
  -PjmhHuman=$PWD/results/$(date +%Y%m%d)-jmh/jmh-output.txt
```

Optional properties: `-PjmhIncludes=<regex> -PjmhFork=3 -PjmhIterations=10 -PjmhWarmupIterations=10 -PjmhTime=2s`.
You can also run the jar directly:
`java -jar build/libs/benchmark-jackson-blackbird-app-jmh.jar JacksonBlackbirdBenchmark -prof gc`.
The Gradle daemon for this build needs JDK 17+ (`gradle/gradle-daemon-jvm.properties` asks for 21).

## 2. Demo app + run-bench.sh

| config | what runs |
|---|---|
| `noagent` | the app alone (Blackbird is on the classpath but not registered) |
| `agent` | `-javaagent:dd-java-agent.jar`, `-Ddd.trace.experimental.jackson.blackbird.enabled=false` |
| `agent-blackbird` | same as `agent`, with `-Ddd.trace.experimental.jackson.blackbird.enabled=true` |
| `noagent-blackbird` (opt-in) | no agent; the app registers Blackbird on Spring's mapper itself (`-Dbench.manual.blackbird=true`) to show the in-app ceiling |

| path | per-request work |
|---|---|
| `/nojson` | control: returns a ~5 KB constant `text/plain` string, no Jackson |
| `/json` | POST of a ~1.9 KB order. Spring MVC deserializes it with its ObjectMapper and serializes a ~4.8 KB response. |
| `/jsonlist` | GET: Spring serializes a list of 100 small DTOs (~17.7 KB) |
| `/newmapper` | anti-pattern: `new ObjectMapper()` for every request, reading the same order and writing the same response. Shows the per-mapper cost and the effect of the cap. |
| `/bench/request-body` | the canonical POST body. LoadGen fetches it once. |
| `/admin/stats` | process CPU time, GC, loaded classes, and whether Spring's mapper (and a mapper created at startup) has Blackbird |

### run-bench.sh options

```
./run-bench.sh -a path/to/dd-java-agent.jar [options]
  -a JAR               dd-java-agent jar (required unless --configs is only noagent)
  -o DIR               output dir (default: ./results/<timestamp>)
  --startup-runs N     startup runs per config (default 5)
  --warmup S           warmup seconds per endpoint (default 15)
  --duration S         measured seconds per endpoint (default 30)
  --connections N      concurrent keep-alive connections (default 8)
  --configs LIST       comma-separated, from noagent,agent,agent-blackbird,noagent-blackbird
                       (default: noagent,agent,agent-blackbird)
  --endpoints LIST     default: nojson,json,jsonlist,newmapper
  --skip-build         reuse build/libs jars
  --heap SIZE          -Xms/-Xmx for the app (default 512m)
Env: BENCH_JAVA_HOME (JDK 17+), APP_PORT (18080), FAKE_AGENT_PORT (18126)
```

For each config: startup to first successful request (median of N runs), then per-endpoint warmup and
a fixed-duration closed-loop load, then one saved response per endpoint so the harness can check the
bytes are identical across configs.

The tools in `json-bench-tools.jar` (`src/loadgen`) are plain JDK and run outside the measured JVM:

- `LoadGen run|wait|get|snapshot`: closed-loop load with N keep-alive connections, reporting
  throughput, latency percentiles and the server's CPU time delta from `/admin/stats`. Endpoints in
  `--post` (default `json,newmapper`) are POSTed with the body from `/bench/request-body`.
- `StartupProbe`: spawns the server, polls until it returns 200, kills it, and repeats after one
  discarded run that warms the OS file cache.
- `FakeAgent PORT`: accepts every trace payload with 200 and counts them at `GET /bench/trace-payloads`.
- `Report results.tsv OUT_DIR "key=value;..."`: writes `summary.json` and `summary.md`, using the first
  config as the baseline.

### Measurement commands

You need JDK 17+ (`BENCH_JAVA_HOME`, or found with `/usr/libexec/java_home -v 17+` on macOS) and the
agent jar built from this branch:

```shell
# from the repository root
./gradlew :dd-java-agent:shadowJar
cd dd-java-agent/benchmark-jackson-blackbird-app
./run-bench.sh -a ../build/libs/dd-java-agent-*-SNAPSHOT.jar -o results/$(date +%Y%m%d)-runbench-pass1
# second pass in reverse order, to expose drift from ordering and thermal effects
./run-bench.sh -a ../build/libs/dd-java-agent-*-SNAPSHOT.jar --skip-build \
  --configs agent-blackbird,agent,noagent -o results/$(date +%Y%m%d)-runbench-pass2-reversed
```

Each config runs 5 startups plus 4 endpoints x (15 s warmup + 30 s measured), with 8 connections. One
pass takes about 10 minutes for the three default configs. Add `--configs noagent,agent,agent-blackbird,noagent-blackbird`
to include the in-app ceiling, which adds about 3.5 minutes. For a second pass, the relative columns
compare against the first config listed, so read pass 2 against pass 1 by config name.

Smoke run (the numbers mean nothing; it only checks that the harness works, in about 1.5 minutes):

```shell
./run-bench.sh -a ../build/libs/dd-java-agent-*-SNAPSHOT.jar --startup-runs 1 --warmup 1 --duration 2 --connections 2
```

Outputs go to `results/<timestamp>/` (or `-o DIR`). Note that `results/` is gitignored, so use
`git add -f` to keep a run.

- `results.tsv`: raw rows (`startup` and `load`)
- `summary.json`: machine-readable summary
- `summary.md`: startup median; per endpoint req/s, p50/p99, CPU us/request (all with deltas against
  the first config), GC ms and classes loaded; Blackbird status per config; and **response bytes per
  config compared with the first config**. Every endpoint must say `identical: yes`. `run-bench.sh` warns
  if any response differs.
- `responses/`: one saved response body per config and endpoint
- `logs/`: app logs and each config's exact command line

The agent baseline is the same as in the regex POC. Tracing is on and sends to `FakeAgent` on
`FAKE_AGENT_PORT` (default 18126). Remote config, telemetry, profiling, AppSec, IAST, DI, DSM,
JMXFetch, health metrics, client stats, crash tracking and startup logs are off. All configs share
`-Xms512m -Xmx512m -XX:+UseG1GC`. Ports can be moved with `APP_PORT` / `FAKE_AGENT_PORT`.

### Read results with care

- CPU us/request is the robust number. Throughput depends on whichever side saturates first, and the
  load generator shares the box with the app.
- `LoadGen` sends a POST as a single write (headers and body together). An earlier version used
  `setFixedLengthStreamingMode`, which sent them separately. Nagle and delayed ACK then put a floor of
  about 1.3 ms on every POST, and `/json` dropped from about 31k to 1.4k req/s. Smoke numbers from
  before that fix are invalid.
- Endpoints run in a fixed order in one JVM, so JIT and GC state carry over. Rerun a single endpoint
  with `--endpoints json` if a number looks off.
- `/newmapper` with `agent-blackbird` pays the Blackbird cost on the first 64 mappers only (about 2 ms
  each). After that it should match `agent`. If `classes loaded` keeps climbing there, the cap is not
  working.

## Indicative smoke numbers (not measurements)

These were taken on 2026-10-30 on an Apple M-series laptop with 16 cpus and Zulu 21, under load from
other builds. They come from tiny durations.

- JMH, 1 fork, 1x300 ms iterations, plain → blackbird:
  - small: ser 264 → 219 ns, deser 375 → 330 ns
  - medium: ser 1390 → 1246 ns, deser 2383 → 1967 ns
  - list100: ser 24.1 → 19.8 us, deser 38.6 → 33.3 us
  - first use of a new mapper: 240 → 2170 us

  That is roughly 10-18% faster steady state and about 9x more expensive on first use.
- run-bench, 2 connections, 3 s warmup + 3 s measured, CPU us/req for noagent / agent / agent-blackbird:
  - `/json`: 49.6 / 62.6 / 54.1
  - `/jsonlist`: 54.1 / 75.0 / 74.2
  - `/newmapper`: 175 / 187 / 184
  - `/nojson`: 27.5 / 46.8 / 40.4

  Startup medians were 864 / 1596 / 1444 ms. Every response was byte-identical across configs. Expect
  the gain on `/json` to be a few us per request at most, small compared with the agent's own overhead.
  The real measurement decides.

## What shipping this for real would require

The POC never ships Blackbird. It only registers it when the application already has
`jackson-module-blackbird` on the mapper's class loader. Most applications don't, so to help them for
real:

1. **Version matching.** Blackbird's internals (`BeanPropertyWriter` / `SettableBeanProperty`
   subclasses and the deserializer/serializer modifiers) are tied to jackson-databind's minor version,
   and Jackson releases and supports its modules in lockstep with databind minor versions. Options:
   - Bundle a version matrix: one shaded Blackbird per databind minor from 2.12 to 2.21 (the latest
     muzzle checks). Pick one at match time from databind's `PackageVersion`, and skip unknown or
     newer minors. That is about 10 jars of about 60-65 KB each, about 0.6 MB in the agent, plus a release
     process every time Jackson ships a minor.
   - Or bundle the source for one version and compile it against each databind minor at build time
     (the same shape as the matrix, with a smaller repo footprint).
2. **Injection.** Blackbird has to be defined in the application's class loader next to databind,
   because it subclasses databind types and resolves bean classes through `MethodHandles.Lookup`. It
   would be injected as helper classes. The agent's shaded copy can't live on the bootstrap or agent
   loader. The helper list would be the whole Blackbird package (36 classes in 2.12.0, including
   inner classes), and helpers would have to be declared by name per version, since automatic
   helper discovery follows only advice references.
3. **Muzzle and tests per supported minor.** Run the differential corpus in this module against every
   databind minor (a `latestDepTest` plus pinned versions), and also against Kotlin, Scala, Joda,
   Hibernate and Afterburner modules.
4. **Policy questions.**
   - Mapper subclasses (XmlMapper, YAMLMapper, `JsonMapper` subclasses in frameworks) are skipped
     today.
   - `getRegisteredModuleIds()` now shows Blackbird. An app that inspects it sees a difference.
   - An app that registers Afterburner later ends up with both. The tests show the output stays
     identical, but it is wasted work.
   - The 64-mapper cap is a heuristic. Apps that create many long-lived mappers lose the speedup past
     the cap.
   - Blackbird generates hidden classes per mapper and bean type, which increases metaspace usage.
   - Java 11+ only.
   - The agent registers a default `new BlackbirdModule()` as soon as the mapper is constructed. Jackson
     drops later registrations of a module with the same type id, so an app that registers its own
     `new BlackbirdModule(customLookup)` afterwards (for JPMS or cross-loader access) silently keeps the
     agent's default module, and its lookup function never runs.
   - `copy()` inherits the agent's Blackbird modifiers but starts with empty serializer caches, and copies
     are not counted against the 64-mapper cap. An app that copies a mapper per request regenerates
     accessors on every request, like the `/newmapper` anti-pattern without the cap.
