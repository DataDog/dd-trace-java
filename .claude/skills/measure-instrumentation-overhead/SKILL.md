---
name: measure-instrumentation-overhead
description: >
  Use when asked to measure or profile the per-call runtime overhead of a Datadog
  Java APM instrumentation under `dd-java-agent/instrumentation/<name>/`, see how
  much an integration costs (allocations, class loads, CPU, wall-clock ns), iterate
  on overhead reductions, or compare with-vs-without-agent for a specific integration.
  Triggers: "measure overhead", "profile instrumentation", "how much does X cost",
  "iterate on overhead", "optimize this instrumentation".
---

Run a JMH benchmark against the integration's test classpath, twice: once with `-javaagent:`, once without. Diff the two JFR recordings.

JMH's time-based warmup (default 5 s × 5 iterations = 25 s) guarantees full JIT before measurement. Existing tests run too few iterations to produce reliable JFR data — run standalone instead.

## Inputs

Required: integration name (`kafka`) or module path (`dd-java-agent/instrumentation/kafka/kafka-clients-0.11`).

Optional flags:
- `--driver h2|test|<custom>` — workload setup. Default `h2`. `test` understates overhead (mock accessors short-circuit real code paths).
- `--warmup N` — JMH warmup iterations. Default **5** (each runs `--time` s).
- `--measured N` — JMH measurement iterations. Default **5**.
- `--time T` — seconds per iteration. Default **5**.
- `--workload <path>` — pre-written `@Benchmark` class; skip auto-generation.
- `--rerun` — reuse previous `Workload.java`, classpath, and `baseline.jfr`; re-record only `instrumented.jfr`.
- `--auto-optimize` — after the report, test each integration-scoped suggestion in isolation. See `auto-optimize.md`.

## Limitations — print before every run

- **Synthetic workload.** Numbers are tight-loop amortised cost, not production steady-state.
- **`NoOpWriter`** excludes writer serialisation/transport from per-call numbers. Use `-Ddd.writer.type=LoggingWriter` to include it.
- **Inlined `@Advice`** appears under host-class frames (`java.sql.*`, `io.netty.*`, etc.), not `datadog.trace.instrumentation.*`. Non-inlined helpers appear under `datadog.trace.instrumentation.*`. Bucket [B] captures both.
- **JMH handles JIT warmup.** The old hand-rolled loop sometimes under-warmed complex server-side integrations — that concern is gone.
- **Class-load deltas**: bucket by package prefix; raw set differences are unreliable (JVM-generated lambda names differ between runs).
- **Indirect GC costs** are captured only weakly.

## Procedure

### 1. Locate the module
Glob `dd-java-agent/instrumentation/**/build.gradle`. If multiple match, list and ask.

### 2. Identify integration names
Grep `src/main/java/**/*Instrumentation.java` for `super("...")` in `Instrumenter`/`InstrumenterModule` subclasses. Record all names.

### 3. Read tests
Read 2-3 non-`ForkedTest`, non-`@Flaky` concrete test classes from `<module>/src/test/`. Prefer tests that use embedded infra (H2, Derby, EmbeddedChannel). Show the hot-path pattern to the user.

### 4. Draft `Workload.java`

Work-dir: `/tmp/overhead-<name>-$(date +%Y%m%d-%H%M%S)/`

Default template (JDBC, H2):
```java
package overhead;

import java.sql.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class Workload {

  private Connection conn;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    conn = DriverManager.getConnection("jdbc:h2:mem:overhead");
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    conn.close();
  }

  @Benchmark
  public boolean benchmark() throws Exception {
    try (PreparedStatement ps = conn.prepareStatement("SELECT 1")) {
      return ps.execute();
    }
  }
}
```

**Variant — active parent span** (propagation-only integrations): open a span in `@Setup(Level.Trial)`, close in `@TearDown`. Add `dd-trace-ot-<version>.jar` + `opentracing-{api,util,noop}-0.33.0.jar` from `~/.gradle/caches/modules-2/files-2.1/io.opentracing/` to the classpath.

```java
  private io.opentracing.Span parent;
  private io.opentracing.Scope scope;

  @Setup(Level.Trial)
  public void setup() {
    parent = io.opentracing.util.GlobalTracer.get().buildSpan("overhead-parent").start();
    scope  = io.opentracing.util.GlobalTracer.get().activateSpan(parent);
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    scope.close();
    parent.finish();
  }
```

Show the draft. Accept edits. **Don't run until confirmed.**

### 4.5. Locate JMH jars → `<work-dir>/jmh_cp.txt`

Skip if `jmh_cp.txt` exists (`--rerun`).

```python
import glob, os
root = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
arts = {
  "jmh-core":                 ("org.openjdk.jmh",   "jmh-core"),
  "jmh-generator-annprocess": ("org.openjdk.jmh",   "jmh-generator-annprocess"),
  "jmh-generator-asm":        ("org.openjdk.jmh",   "jmh-generator-asm"),
  "jmh-generator-bytecode":   ("org.openjdk.jmh",   "jmh-generator-bytecode"),
  "jopt-simple":              ("net.sf.jopt-simple", "jopt-simple"),
  "commons-math3":            ("org.apache.commons", "commons-math3"),
}
jars = []
for name, (g, a) in arts.items():
  hits = [j for j in glob.glob(f"{root}/{g}/{a}/**/*.jar", recursive=True)
          if "sources" not in j and "javadoc" not in j]
  if hits: jars.append(sorted(hits)[-1])
  else: print(f"MISSING: {g}:{a}")
open("<work-dir>/jmh_cp.txt", "w").write(":".join(jars))
```

Stop if `jmh-core` is missing. For `jmh-generator-annprocess`: it may be absent from the Gradle module cache even after `./gradlew :dd-java-agent:benchmark:dependencies`; if so, download it directly:
```bash
curl -sL https://repo1.maven.org/maven2/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar \
  -o ~/.gradle/caches/modules-2/files-2.1/org.openjdk.jmh/jmh-generator-annprocess/1.37/downloaded/jmh-generator-annprocess-1.37.jar
```
Then re-run the jar-location script above.

### 5. Build agent jar

```bash
./gradlew :dd-java-agent:shadowJar
ls dd-java-agent/build/libs/dd-java-agent-*.jar | sort | tail -1
```

### 6. Compile test classes + capture classpath

Skip on `--rerun`.

```bash
./gradlew :<module>:testClasses
```

Write `<work-dir>/init.gradle`:
```groovy
allprojects { afterEvaluate {
  if (project.path == System.getenv('DD_OVERHEAD_MODULE')) {
    tasks.register('printTestClasspath') { doLast {
      new File(System.getenv('DD_OVERHEAD_CLASSPATH_FILE')).text =
        sourceSets.test.runtimeClasspath.asPath
    }}
  }
}}
```

```bash
DD_OVERHEAD_MODULE=:<gradle-path> \
DD_OVERHEAD_CLASSPATH_FILE=<work-dir>/classpath.txt \
./gradlew :<gradle-path>:printTestClasspath --init-script <work-dir>/init.gradle
```

### 7. Validate JFC

```bash
xmllint --noout <skill-dir>/overhead.jfc
java -XX:StartFlightRecording=settings=<skill-dir>/overhead.jfc,filename=/tmp/_jfc-smoke.jfr,duration=1s -version
rm -f /tmp/_jfc-smoke.jfr
cp <skill-dir>/overhead.jfc <work-dir>/overhead.jfc
```

Stop if `xmllint` or the JVM invocation fails.

### 8. Compile workload

```bash
JMH_CP=$(cat <work-dir>/jmh_cp.txt)
javac --release 8 \
  -processorpath "$JMH_CP" \
  -d <work-dir>/classes \
  -cp "$(cat <work-dir>/classpath.txt):$JMH_CP" \
  <work-dir>/Workload.java
```

`-processorpath` must include `jmh-generator-annprocess`, or JMH reports "No benchmarks found". Stop on compile error.

### 9. Run instrumented

```bash
WORKDIR=<work-dir>  AGENT_JAR=<agent.jar>
JMH_CP=$(cat "$WORKDIR/jmh_cp.txt")
WI=${warmup:-5}  I=${measured:-5}  T=${time:-5}

java -cp "$WORKDIR/classes:$(cat $WORKDIR/classpath.txt):$JMH_CP" \
  org.openjdk.jmh.Main "overhead.Workload" \
  -f 1 -wi "$WI" -w "${T}s" -i "$I" -r "${T}s" -bm avgt -tu ns \
  -jvmArgs "-javaagent:$AGENT_JAR -Ddd.profiling.enabled=false \
    -Ddd.writer.type=NoOpWriter -Ddd.trace.startup.logs=false \
    -XX:StartFlightRecording=settings=$WORKDIR/overhead.jfc,\
filename=$WORKDIR/instrumented.jfr,dumponexit=true,maxsize=512m" \
  -rf json -rff "$WORKDIR/instrumented.json" \
  2>&1 | tee "$WORKDIR/instrumented_jmh.log"
```

Extract per-call ns:
```bash
python3 -c "
import json; d=json.load(open('$WORKDIR/instrumented.json'))
print(f'per_call_ns={d[0][\"primaryMetric\"][\"score\"]:.0f} ±{d[0][\"primaryMetric\"][\"scoreError\"]:.0f} ns')
"
```

On non-zero exit: surface `instrumented_jmh.log` and still attempt JFR analysis (`dumponexit=true` flushes partial data).

### 10. Run baseline

Same command **without** `-javaagent:` and `dd.*` properties. Output: `baseline.jfr` + `baseline.json`. Skip on `--rerun` if both exist.

```bash
java -cp "$WORKDIR/classes:$(cat $WORKDIR/classpath.txt):$JMH_CP" \
  org.openjdk.jmh.Main "overhead.Workload" \
  -f 1 -wi "$WI" -w "${T}s" -i "$I" -r "${T}s" -bm avgt -tu ns \
  -jvmArgs "-XX:StartFlightRecording=settings=$WORKDIR/overhead.jfc,\
filename=$WORKDIR/baseline.jfr,dumponexit=true,maxsize=512m" \
  -rf json -rff "$WORKDIR/baseline.json" \
  2>&1 | tee "$WORKDIR/baseline_jmh.log"
```

### 11. Analyze JFRs

```bash
jfr print --events ObjectAllocationInNewTLAB,ObjectAllocationOutsideTLAB --stack-depth 40 <file>
jfr print --events ExecutionSample --stack-depth 40 <file>
jfr print --events ThreadCPULoad <file>
jfr print --events ClassLoad --stack-depth 0 <file>   # --stack-depth 0 avoids NPE on JDK 25+
jfr view --width 160 hot-methods <file>       > <work-dir>/{instr,base}_hotmethods.txt
jfr view --width 160 thread-allocation <file> > <work-dir>/{instr,base}_thread_alloc.txt
```

**Bucket each allocation and CPU event** using the 4-bucket model (see appendix). Build the [B] host-class set from: the workload's imports + the integration's `Instrumenter.typeMatcher()` + any embedded driver. Show the set to the user.

**HTTP server/client integrations**: [D] often reveals the real overhead (URI parsing, span building, propagation extraction). Analyse [D] top frames alongside [B].

If [B] < 100 events: warn, label results "low-confidence". With JMH defaults this is rare — verify `@Benchmark` reaches the hot path.

**URI allocation pattern** (`URIUtils.safeParse` / `URIDataAdapterBase.fromURI` in [D]): the fix is integration-scoped — subclass `URIRawDataAdapter` (15+ integrations already use this). Do NOT label this "agent-core".

**Flamegraphs** (`<skill-dir>/flamegraph.py`):
```bash
SKILL_DIR=<skill-dir>
python3 "$SKILL_DIR/flamegraph.py" <work-dir>/instr_cpu.txt   <work-dir>/instrumented_cpu.svg   --title "Instrumented CPU"
python3 "$SKILL_DIR/flamegraph.py" <work-dir>/base_cpu.txt    <work-dir>/baseline_cpu.svg       --title "Baseline CPU"
python3 "$SKILL_DIR/flamegraph.py" <work-dir>/instr_alloc.txt <work-dir>/instrumented_alloc.svg --title "Instrumented TLAB"
python3 "$SKILL_DIR/flamegraph.py" <work-dir>/base_alloc.txt  <work-dir>/baseline_alloc.svg     --title "Baseline TLAB"
```

**ASCII histogram** of top-10 [B] allocation types (embed in report):
```python
all_types = sorted(set(list(bytype_instr)+list(bytype_base)),
                   key=lambda t: -(bytype_instr.get(t,0)+bytype_base.get(t,0)))[:10]
max_c = max(max(bytype_instr.values(),default=0), max(bytype_base.values(),default=0), default=1)
W = 22
lines = ["### Allocation [B] histogram", "```"]
for t in all_types:
  ic=bytype_instr.get(t,0); bc=bytype_base.get(t,0)
  ib='█'*max(1 if ic else 0,round(ic/max_c*W))
  bb='█'*max(1 if bc else 0,round(bc/max_c*W))
  n=t[-44:] if len(t)>44 else t
  lines.append(f"  {n:<46}  instr [{ib:<{W}}]{ic:>5}   base [{bb:<{W}}]{bc:>5}")
lines.append("```")
```

### 12. Report

**Chat summary** (mandatory, always):
- Per-call ns: `instrumented − baseline` from JMH JSON `primaryMetric.score ± scoreError`. Headline.
- Allocations: per-bucket totals + top 3 [B] methods.
- CPU: per-bucket sample counts + top 3 [B] methods.
- Class-footprint delta by package prefix.
- `dd-*` thread CPU if any > 5%.
- One-line limitations restatement.

**`<work-dir>/report.md`** must include: inputs, integration names, JDK version, workload source, per-bucket tables, ClassLoad table, per-thread CPU, inlined-advice attribution table, JFR file paths.

**Mandatory sections in report.md:**
```markdown
## Visualizations
| File | Description |
|---|---|
| [instr_hotmethods.txt](instr_hotmethods.txt) | hot-methods — instrumented |
| [base_hotmethods.txt](base_hotmethods.txt) | hot-methods — baseline |
| [instrumented_cpu.svg](instrumented_cpu.svg) | CPU flamegraph — instrumented |
| [baseline_cpu.svg](baseline_cpu.svg) | CPU flamegraph — baseline |
| [instrumented_alloc.svg](instrumented_alloc.svg) | TLAB flamegraph — instrumented |
| [baseline_alloc.svg](baseline_alloc.svg) | TLAB flamegraph — baseline |

### Allocation [B] histogram — instrumented vs baseline
<embed ASCII histogram>

### Hot methods — instrumented (top 10)
<embed first 15 lines of instr_hotmethods.txt>
```

**Mandatory: "Code improvements"** — for each: file:line, relevant snippet, 1-2 sentence fix, motivating JFR events, ranked by ROI. Include "Not pursued" subsection. Flag agent-core suggestions as "agent-core, for context".

**`<work-dir>/suggestions.json`**:
```json
{
  "id": 1,
  "scope": "integration",
  "file": "absolute/path",
  "anchor": "human-readable location",
  "find": "exact snippet — must be unique in file",
  "replace": "replacement",
  "description": "one line",
  "target_frames": {
    "allocation": ["fq.Class.method"],
    "cpu": []
  }
}
```
`scope`: `"integration"` (auto-applied) or `"core"` (skipped). `find: null` for multi-method refactors. Omit entries without a clear target frame from `suggestions.json`; keep them in `report.md` marked "(manual review only)".

Print the report path.

### 13. Persist run state

**`<work-dir>/run.json`**:
```json
{
  "module": "<gradle-path>",
  "fqcn": "overhead.Workload",
  "workload_java": "<work-dir>/Workload.java",
  "classpath_file": "<work-dir>/classpath.txt",
  "jmh_cp_file": "<work-dir>/jmh_cp.txt",
  "agent_jar": "<absolute-path>",
  "agent_version": "<version>",
  "jmh_warmup_iterations": 5,
  "jmh_measured_iterations": 5,
  "jmh_time_seconds": 5,
  "driver": "h2",
  "integration_names": ["..."]
}
```

Ask verbatim: *"I generated N integration-scoped suggestions. Want me to try them with `--auto-optimize`? [y/N]"*

## Failure modes

| Symptom | Fix |
|---|---|
| No matching module | List `dd-java-agent/instrumentation/*/` and ask |
| No tests in module | Ask for a workload pattern; offer `--workload <path>` |
| Agent jar missing | Surface Gradle error verbatim |
| `javac` compile error | Surface verbatim; abort |
| `No benchmarks found` (JMH) | Recompile with `-processorpath` from step 8 |
| JMH jar missing | `./gradlew :dd-java-agent:benchmark:dependencies` |
| JMH exits non-zero | Surface log; still attempt JFR analysis |
| JFR file absent | Check JMH log for fork stderr |
| `jfr` not on PATH | Set `JAVA_HOME` to a full JDK (17+) |
| `jfr print` NPE on ClassLoad (JDK 25+) | Add `--stack-depth 0` |
| Bucket [B] < 100 events | Warn; verify `@Benchmark` reaches the hot path |
| Integration name ambiguous | Ask the user |
| Host-class set unclear | List candidates from imports + `typeMatcher()`; ask |
| Flamegraph SVG empty | Check JMH log; verify the benchmark ran |

## Conventions

- Never modify production code, test code, build files, or `overhead.jfc`.
- Never commit.
- Artifacts go under `/tmp/overhead-...`; never under the repo.
- Chat output: concise. Detail goes in `report.md`.
- Always restate limitations at the start of every run, including `--rerun`.
- Never add cross-integration comparison tables (different workloads, not comparable).
- Auto-mode is non-destructive: no commits; reverts use `Read`+`Write`, never `git checkout`; standard report is never modified.
- `flamegraph.py` is pure Python 3 stdlib. Always run it.

## 4-bucket reference

Assign each JFR event to the **first** matching bucket by walking its stack innermost-first:

| Bucket | Frame prefixes | Notes |
|---|---|---|
| **[A]** Setup/transform | `net.bytebuddy.*`, `datadog.trace.agent.tooling.bytebuddy.*`, `datadog.trace.agent.tooling.AgentInstaller*`, `datadog.trace.agent.tooling.muzzle.*` | Excluded from headline |
| **[B]** Integration | Integration host classes (e.g. `java.sql.*`, `io.netty.*`, `org.h2.*`) OR `datadog.trace.instrumentation.<name>.*` | **Headline bucket** |
| **[C]** Tracer init | `datadog.trace.api.Config*`, `datadog.trace.core.CoreTracer.<init>`, `datadog.trace.core.scopemanager.*`, `datadog.trace.bootstrap.Agent` | One-time cost |
| **[D]** Other agent | Anything else in `datadog.*` / `com.datadog.*` | Often reveals HTTP integration overhead |
| **[J]** JFR self | `jdk.jfr.*` | Report separately; subtract from [D] |
