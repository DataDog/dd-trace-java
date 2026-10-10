# Design: JMH Benchmark CI Visibility Instrumentation (SDTEST-930)

## Problem

JMH (Java Microbenchmark Harness, `org.openjdk.jmh`, version 1.37) is the dominant Java
benchmarking framework. Benchmark runs are not currently reported to CI Visibility, so
performance regressions are invisible in the Datadog test explorer.

## Goals

- Report each JMH benchmark method as a CI Visibility **test span** (`test.type = "benchmark"`)
- Attach aggregated performance metrics (score, error, unit, percentiles) as span tags
- **Zero overhead on the benchmark hot path** — hook only fires once per benchmark method, not per invocation
- Disabled by default (like Renaissance); opt-in via `DD_TRACE_JMH_ENABLED=true`

## JMH Lifecycle and Hook Point

JMH execution flow (per benchmark method):

```
Runner.run()
  └─ for each benchmark method:
       OutputFormat.startBenchmark(BenchmarkParams)   ← span start
       for each fork:
         for each warmup iteration:
           OutputFormat.iteration(...)
         for each measurement iteration:
           OutputFormat.iterationResult(...)
           OutputFormat.iteration(...)
       OutputFormat.endBenchmark(BenchmarkResult)     ← span finish + attach metrics
  └─ OutputFormat.endRun(Collection<RunResult>)
```

`OutputFormat` is an interface that JMH calls for all lifecycle events. Critically:
- `startBenchmark(BenchmarkParams)` fires **once** per benchmark method before any invocations
- `endBenchmark(BenchmarkResult)` fires **once** per benchmark method after all forks and iterations

These are the only two hooks needed. No hot-path instrumentation is required.

### Why not instrument `@Benchmark`-annotated methods directly?

Those methods are called millions of times during warmup and measurement. Advice on the
hot path would perturb the benchmark results and add massive instrumentation overhead.

## Instrumentation Strategy

### Hook: `OutputFormat` injection

JMH's `Runner` is constructed by user code:

```java
Runner runner = new Runner(options);
runner.run();
```

The `Runner` constructor accepts an `Options` object, which includes an `OutputFormat`. We
instrument `Runner.<init>` to wrap the user-supplied `OutputFormat` with our own
`DDOutputFormat` decorator before the field is stored.

**Bytecode advice on `Runner.<init>`:**

```java
@Advice.OnMethodExit
public static void onExit(@Advice.FieldValue(value = "out", readOnly = false) OutputFormat out) {
    out = new DDOutputFormat(out);
}
```

`Runner.out` is the `OutputFormat` field. Wrapping it at construction time means our
decorator receives all lifecycle callbacks without any per-invocation cost.

### Alternative: instrument `Runner.run()` return value

If wrapping the constructor is fragile due to JMH refactors, a fallback is to instrument
`Runner.run()` / `Runner.runBenchmarks()` exit and iterate the returned
`Collection<RunResult>` to emit spans retroactively. This loses wall-clock timing fidelity
(span duration reflects only the post-run callback time) but is simpler and requires no
field access.

The constructor approach is preferred; the `run()` return approach is the fallback.

## Data Model

### Span structure

Each JMH benchmark method produces **two spans**:

| Span | Mapping | Notes |
|------|---------|-------|
| Test suite span | Benchmark class (e.g., `com.example.MyBenchmark`) | One per class |
| Test span | Benchmark method (e.g., `myMethod`) | One per `@Benchmark` method per parameter set |

`BenchmarkParams.getBenchmark()` returns the fully-qualified name
`"com.example.MyBenchmark.myMethod"` — split on the last `.` to get class and method.

### Standard CI Visibility tags

| Tag | Source | Value |
|-----|--------|-------|
| `test.type` | constant | `"benchmark"` |
| `test.framework` | constant | `"jmh"` |
| `test.framework_version` | `Version.getVersion(Runner.class)` | e.g. `"1.37"` |
| `test.name` | `BenchmarkParams.getBenchmark()` last segment | e.g. `"myMethod"` |
| `test.suite` | `BenchmarkParams.getBenchmark()` prefix | e.g. `"com.example.MyBenchmark"` |
| `test.status` | always `"pass"` (JMH throws on error) | `"fail"` if exception from `endBenchmark` |
| `test.parameters` | `BenchmarkParams.getParamsKeys()` + `getParam(key)` | JSON object, omit if empty |
| `test.source.class` | derived from suite name | class name |
| `test.source.method` | derived from test name | method name |

### Benchmark-specific metric tags

These are numeric tags added on the test span, not on suite spans:

| Tag | Source | Notes |
|-----|--------|-------|
| `benchmark.run.iterations` | `BenchmarkParams.getMeasurement().getCount()` | Measurement iteration count |
| `benchmark.run.forks` | `BenchmarkParams.getForks()` | Fork count |
| `benchmark.run.threads` | `BenchmarkParams.getThreads()` | Thread count |
| `benchmark.run.warmup_iterations` | `BenchmarkParams.getWarmup().getCount()` | Warmup iteration count |
| `benchmark.run.time_unit` | `BenchmarkParams.getTimeUnit().name()` | e.g. `"NANOSECONDS"` |
| `benchmark.run.mode` | `BenchmarkParams.getMode().shortLabel()` | e.g. `"thrpt"`, `"avgt"` |
| `benchmark.value` | `Result.getScore()` | Primary metric score |
| `benchmark.error` | `Result.getScoreError()` | 99.9% CI half-width; `NaN` for single-shot |
| `benchmark.unit` | `Result.getScoreUnit()` | e.g. `"ops/ms"`, `"ns/op"` |
| `benchmark.p50` | `Statistics.getPercentile(50)` | Median |
| `benchmark.p90` | `Statistics.getPercentile(90)` | |
| `benchmark.p95` | `Statistics.getPercentile(95)` | |
| `benchmark.p99` | `Statistics.getPercentile(99)` | |
| `benchmark.min` | `Statistics.getMin()` | |
| `benchmark.max` | `Statistics.getMax()` | |
| `benchmark.sample_count` | `Statistics.getN()` | Total sample count |

`Statistics` is available via `BenchmarkResult.getPrimaryResult().getStatistics()`.

For `SingleShotTime` mode, only `benchmark.value` and `benchmark.unit` are populated
(no iterations → no distribution).

### Access path summary (zero hot-path calls)

```
endBenchmark(BenchmarkResult result)
├── result.getParams()                              → BenchmarkParams (all config)
│   ├── .getBenchmark()                             → "com.example.MyBenchmark.myMethod"
│   ├── .getMode().shortLabel()                     → "thrpt"
│   ├── .getThreads()                               → 4
│   ├── .getForks()                                 → 5
│   ├── .getMeasurement().getCount()                → 5
│   ├── .getWarmup().getCount()                     → 5
│   ├── .getTimeUnit()                              → NANOSECONDS
│   └── .getParamsKeys() + .getParam(key)           → {"size": "1000"}
└── result.getPrimaryResult()                       → Result
    ├── .getScore()                                 → 1234.56
    ├── .getScoreError()                            → 12.34
    ├── .getScoreUnit()                             → "ns/op"
    └── .getStatistics()                            → Statistics
        ├── .getPercentile(50/90/95/99)             → distribution
        ├── .getMin() / .getMax()                   → bounds
        └── .getN()                                 → sample count
```

## Module Layout

```
dd-java-agent/instrumentation/jmh/
└── jmh-1.0/                         # JMH's OutputFormat API is stable since 1.0
    ├── build.gradle
    ├── gradle.lockfile
    └── src/
        └── main/java/datadog/trace/instrumentation/jmh/
            ├── JmhInstrumentation.java       # InstrumenterModule targeting Runner.<init>
            ├── DDOutputFormat.java           # OutputFormat decorator
            └── JmhUtils.java                 # Parsing helpers (benchmark name split, etc.)
```

### `build.gradle`

```groovy
apply from: "$rootDir/gradle/java.gradle"

dependencies {
  compileOnly group: 'org.openjdk.jmh', name: 'jmh-core', version: '1.0'
  testImplementation group: 'org.openjdk.jmh', name: 'jmh-core', version: '1.37'
}
```

## Changes Required Outside the New Module

| File | Change |
|------|--------|
| `internal-api/.../telemetry/tag/TestFrameworkInstrumentation.java` | Add `JMH` enum constant |
| `internal-api/.../bootstrap/instrumentation/api/Tags.java` | Add `benchmark.*` tag constants |
| `internal-api/.../decorator/TestDecorator.java` | Add `TEST_TYPE_BENCHMARK = "benchmark"` constant |
| `dd-java-agent/agent-ci-visibility/.../decorator/TestDecoratorImpl.java` | Handle benchmark type |

## Resolved Design Decisions

### Suite span scope
Flat: each benchmark method (including each `@Param` combination) gets its own suite span
and test span pair. No grouping by class.

### Parameterized benchmarks
Follow the same pattern as JUnit 5 parameterized tests: set `test.parameters` to
`{"metadata":{"test_name":"<display name>"}}` where the display name is the parameterized
suffix of the benchmark name.

JMH encodes `@Param` combinations by appending them after a colon:
`"com.example.MyBenchmark.myMethod:size=1000,threads=4"`

Parsing:
- `test.suite` = everything before the last `.` before the colon: `"com.example.MyBenchmark"`
- `test.name` = method name segment without params: `"myMethod"`
- `test.parameters` = `{"metadata":{"test_name":"myMethod:size=1000,threads=4"}}` (non-null only when a colon is present)

The parameterized variant is a distinct test identity (unique `test.name` + `test.parameters`
combination), matching how JUnit 5 handles `@ParameterizedTest`.

## Open Questions

1. **CI Visibility opt-out for ITR**: JMH benchmarks should likely be excluded from
   Intelligent Test Runner (skip logic) since skipping a benchmark run defeats its purpose.
   Mark them as `@ITRUnskippable` equivalent or configure the handler to always-run.

2. **Forked JVM mode**: When `@Fork(1+)` is used, each fork is a separate JVM process.
   The tracer in the forked process needs to propagate the session/module/suite IDs from
   the parent. This is the same challenge as Gradle worker forks — check if the existing
   IPC mechanism in `ProcessHierarchy` covers it.

## Implementation Order

1. Add `benchmark.*` tag constants to `Tags.java`
2. Add `JMH` to `TestFrameworkInstrumentation`
3. Add `TEST_TYPE_BENCHMARK` to `TestDecorator`
4. Implement `DDOutputFormat` + `JmhInstrumentation` + `JmhUtils`
5. Wire module into the agent's instrumentation list
6. Add JUnit 5 integration tests (use JMH `Runner` programmatically in test; verify spans)
