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

Measure the per-call runtime overhead of a single instrumentation by drafting a synthetic standalone workload (a `main` that hammers the hot-path API), compiling it against the module's test classpath, and running it twice as a plain JVM — once with the production agent attached via `-javaagent:`, once without — and diffing the two JFR recordings.

**Why standalone, not existing tests**: existing instrumentation tests run ~10 calls per method — far too few to populate JFR's integration-host-class bucket. A 10k-iter synthetic workload run outside the test framework (no Spock, no `DDSpecification`, no Gradle test-worker) gives real per-call numbers and a clean JFR.

## Inputs

Required: an instrumentation identifier — a name (`kafka`, `kafka-clients-0.11`) or a module path (`dd-java-agent/instrumentation/kafka/kafka-clients-0.11`).

Optional flags:
- `--driver h2|test|<custom>` — workload setup. Default `h2` (real driver, realistic call graph). `test` uses in-tree mocks (`test.TestConnection` etc.) for fast feedback but **understates real overhead** — the mock's degenerate accessors short-circuit decorator/metadata code paths.
- `--measured N` / `--warmup N` — override the workload's loop counts. Defaults: warmup 1000, measured 10000.
- `--workload <path>` — supply your own pre-written workload `.java` file with a `public static void main`. Skill skips auto-generation.
- `--rerun` — reuse the workload `.java` + captured classpath + last `baseline.jfr` from the previous run dir. Re-records only `instrumented.jfr`. Fast iteration loop.
- `--auto-optimize` — after the standard report, automatically try each integration-scoped suggestion in isolation. See `auto-optimize.md` (in this directory) for the procedure.

## State the limitations up front

Print these in the first message of every run, every time, before any work:

- **The workload is synthetic, not a real application.** Numbers reflect amortized hot-path cost in a tight loop, not steady-state production behaviour, not cold-start cost, not less-frequent code paths.
- **Default writer is `NoOpWriter`** so writer-side serialization/transport doesn't pollute per-call numbers. Production uses `DDAgentWriter` (msgpack + batched + off-thread); realistic production hot-path cost is closer to NoOp than `LoggingWriter`. Use `-Ddd.writer.type=LoggingWriter` to measure writer-on-hot-path cost explicitly.
- **Inlined `@Advice` does not appear under `datadog.trace.instrumentation.<name>.*`** — ByteBuddy `@Advice` is inlined into the host class; allocation/CPU from inlined advice shows up under the host-class frame (`java.sql.*`, `org.h2.*`, `test.Test*`, etc.). Non-inlined helpers (decorators, propagators) DO appear under `datadog.trace.instrumentation.<name>.*`. Both contribute to bucket [B]; the 4-bucket analyzer captures both.
- **`measured` must be high enough to populate bucket [B].** Default 10000 works for span-creating integrations (JDBC, HTTP, messaging clients). For propagation-only integrations whose per-call work is ~few hundred ns (executors, async wrappers, context-tracking), **start at 100000**. The post-run `[B] < 100` check catches under-sampling either way.
- **Per-call ns includes JIT compilation cost if warmup is insufficient.** Default warmup=1000 triggers C1 (client) compilation for simple hot paths; C2 (server/optimizing) typically needs 10 000+ invocations. Raise with `--warmup N` if needed. Step 11 verifies JIT occurred by inspecting `jdk.Compilation` events — if the hot-path method is absent, bump `--warmup` and rerun.
- **Class-load deltas: bucket counts beat raw set differences.** JVM-generated lambda hex names differ between runs; the bucketed count by package prefix is the reliable signal.
- **Indirect costs** (GC pressure shifting unrelated code) caught only weakly because the workload is short.

## Procedure

1. **Locate the module**. Glob `dd-java-agent/instrumentation/**/build.gradle` and match. If multiple match, list candidates and ask. Don't guess.

2. **Identify integration name(s)**. Grep the module's `src/main/java/**/*Instrumentation.java` for `super("...")` constructor calls in `Instrumenter` / `InstrumenterModule` subclasses. Capture the full set — modules often declare several. These are recorded in the report; baseline simply runs without `-javaagent:`, so they're not used to disable anything.

3. **Read 2-3 representative tests** under `<module>/src/test/{java,groovy}` to extract the hot-path call pattern. Pick concrete, non-`*ForkedTest`, non-`@Flaky` classes extending `InstrumentationSpecification` / `VersionedNamingTestBase` / `HttpServerTest` / `HttpClientTest`. Skip abstract `*TestBase`. Watch for tests needing external infra (e.g. `RemoteJDBCInstrumentationTest` needs PostgreSQL/MySQL on localhost) — prefer in-tree mocks or embedded drivers (H2, Derby). Show the picked tests + extracted pattern to the user.

4. **Draft a synthetic workload** at `<work-dir>/Workload.java` (work-dir = `/tmp/overhead-<short-name>-$(date +%Y%m%d-%H%M%S)/`).

   Default template (JDBC, H2):
   ```java
   package overhead;
   import java.sql.*;
   public final class Workload {
     public static void main(String[] args) throws Exception {
       int warmup   = Integer.getInteger("warmup",   1000);
       int measured = Integer.getInteger("measured", 10000);
       try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:overhead")) {
         for (int i = 0; i < warmup;   i++) hotPath(conn, "SELECT 1");
         long start = System.nanoTime();
         for (int i = 0; i < measured; i++) hotPath(conn, "SELECT 1");
         long elapsed = System.nanoTime() - start;
         System.out.println("iterations=" + measured);
         System.out.println("total_ns="   + elapsed);
         System.out.println("per_call_ns=" + (elapsed / measured));
       } catch (Throwable t) {
         t.printStackTrace(System.err);
         System.exit(1);
       }
     }
     private static void hotPath(Connection c, String sql) throws SQLException {
       try (PreparedStatement ps = c.prepareStatement(sql)) { ps.execute(); }
     }
   }
   ```

   **Variant — active parent span (for propagation-only integrations like executors, async wrappers, context-tracking)**: wrap the loop in `Span parent = GlobalTracer.get().buildSpan("overhead-parent").start(); try (Scope s = GlobalTracer.get().activateSpan(parent)) { ... } finally { parent.finish(); }` using `io.opentracing.util.GlobalTracer`. With agent attached → real DDTracer; without → noop. Both compile. **Classpath addendum**: append `dd-trace-ot/build/libs/dd-trace-ot-<version>.jar` (build with `./gradlew :dd-trace-ot:jar` first) + `opentracing-{api,util,noop}-0.33.0.jar` (from `~/.gradle/caches/modules-2/files-2.1/io.opentracing/`).

   Show the draft. Accept edits to warmup/measured/hotPath body/driver/dd.* sysprops. Don't run until confirmed.

5. **Build the agent jar** (idempotent — no `--rerun-tasks`):
   ```
   ./gradlew :dd-java-agent:shadowJar
   ```
   Locate: `ls dd-java-agent/build/libs/dd-java-agent-*.jar | sort | tail -1`.

6. **Compile test classes + capture runtime classpath** (only first run; `--rerun` reuses).
   - `./gradlew :<module-gradle-path>:testClasses`.
   - Capture `sourceSets.test.runtimeClasspath.asPath` via a transient init script that writes to `<work-dir>/classpath.txt`. Pattern:
     ```groovy
     allprojects { afterEvaluate {
       if (project.path == System.getenv('DD_OVERHEAD_MODULE')) {
         tasks.register('printTestClasspath') {
           doLast {
             new File(System.getenv('DD_OVERHEAD_CLASSPATH_FILE')).text =
               sourceSets.test.runtimeClasspath.asPath
           }
         }
       }
     }}
     ```
     Then `DD_OVERHEAD_MODULE=:<gradle-path> DD_OVERHEAD_CLASSPATH_FILE=<work-dir>/classpath.txt ./gradlew :<gradle-path>:printTestClasspath --init-script <work-dir>/init.gradle`.
   - The classpath includes `testImplementation` deps + the Groovy stdlib — required if `--driver test` (because `test.TestConnection` is Groovy).

7. **Pre-flight JFC validation**. The shipped JFR config is at `<skill-dir>/overhead.jfc`. Validate it before launching any test JVM:
   1. `xmllint --noout <skill-dir>/overhead.jfc` — XML well-formedness. JFC is XML and `<!-- ... -->` comments cannot contain `--`, even within text.
   2. `java -XX:StartFlightRecording=settings=<skill-dir>/overhead.jfc,filename=/tmp/_jfc-smoke.jfr,duration=1s -version` — JVM acceptance. If this fails with `Could not parse file '...'`, the JFC is malformed; report verbatim and stop. Clean up `/tmp/_jfc-smoke.jfr` after.
   - Copy the validated JFC to `<work-dir>/overhead.jfc` so the run is self-contained.

8. **Compile the workload**:
   ```
   javac --release 8 -d <work-dir>/classes -cp $(cat <work-dir>/classpath.txt) <work-dir>/Workload.java
   ```
   Surface compiler errors verbatim and abort if compilation fails.

9. **Run instrumented**:
   ```
   /usr/bin/time -p java \
     -javaagent:<agent.jar> \
     -XX:StartFlightRecording=settings=<work-dir>/overhead.jfc,filename=<work-dir>/instrumented.jfr,dumponexit=true,maxsize=512m \
     -Ddd.profiling.enabled=false \
     -Ddd.writer.type=NoOpWriter \
     -Ddd.trace.startup.logs=false \
     -cp <work-dir>/classes:$(cat <work-dir>/classpath.txt) \
     overhead.Workload
   ```
   `NoOpWriter` (`dd-trace-core/.../writer/NoOpWriter.java`) drops finished traces; span lifecycle still runs. Isolates instrumentation runtime from writer cost. To measure with writer-on-hot-path, swap for `LoggingWriter` and note it in the report.

   Capture stdout (per-call ns), stderr, exit code. If exit ≠ 0, surface stderr verbatim and **still attempt JFR analysis** — `dumponexit=true` flushes up to the failure point.

10. **Run baseline**: same `java` invocation **without** `-javaagent:` and **without** the `dd.*` system properties. Output to `<work-dir>/baseline.jfr`. Skip on `--rerun` if `<work-dir>/baseline.jfr` exists.

11. **Analyze** both JFRs.
    - Allocations: `jfr print --events ObjectAllocationInNewTLAB,ObjectAllocationOutsideTLAB --stack-depth 40 <file>`.
    - CPU samples: `jfr print --events ExecutionSample --stack-depth 40 <file>`.
    - Per-thread CPU: `jfr print --events ThreadCPULoad <file>` — surface any `dd-*` thread averaging > 5% CPU.
    - **JIT verification**: `jfr print --events jdk.Compilation --stack-depth 0 <work-dir>/instrumented.jfr` — check whether the workload's hot-path method (`overhead.Workload.hotPath` or equivalent) appears. If absent, JIT did not compile it; bump `--warmup 10000` (10×) and rerun. If present but with a `startTime` late in the recording (close to the per-call timing window), compilation overlapped with measurement; bump `--warmup` to push it into the warmup phase.
    - Class loads: `jfr print --events ClassLoad --stack-depth 0 <file>` (the `--stack-depth 0` works around the JDK 25 `jfr print` NPE on ClassLoad-with-stacks). Bucket loaded class names by package prefix.
    - Place each event (allocation or CPU sample) in the four-bucket model — see "4-bucket reference" appendix. Build the [B] host-class set from the workload's imports + the integration's `Instrumenter.typeMatcher()` patterns + any embedded driver. Show the set to the user.
    - **Validate [B] coverage**: if [B] has fewer than 100 events in the instrumented JFR, warn and suggest bumping `--measured 100000` (10×), adding `-XX:TLABSize=64k`, or verifying the workload reaches the integration's hot path.
    - **`jfr view` summaries** — run after `jfr print`; capture to files, embed in report:
      ```bash
      jfr view --width 160 hot-methods       <file> > <work-dir>/{instr,base}_hotmethods.txt
      jfr view --width 160 thread-allocation <file> > <work-dir>/{instr,base}_thread_alloc.txt
      ```
      Note: `allocation-by-class` / `allocation-by-site` require `jdk.ObjectAllocationSample` which the JFC does not enable; the 4-bucket TLAB analysis is the allocation story.
    - **ASCII allocation histogram** — after bucket analysis, generate a bar-chart of the top-10 [B] allocation types comparing instrumented vs baseline. Embed verbatim in `report.md`. Use this Python snippet (where `bytype_instr` and `bytype_base` are `Counter[{type_name: count}]` built during bucket analysis):
      ```python
      all_types = sorted(set(list(bytype_instr) + list(bytype_base)),
                         key=lambda t: -(bytype_instr.get(t,0) + bytype_base.get(t,0)))[:10]
      max_c = max((max(bytype_instr.values(), default=0),
                   max(bytype_base.values(),  default=0)), default=1)
      W = 22
      lines = ["### Allocation [B] histogram — instrumented vs baseline", "```"]
      for t in all_types:
          ic = bytype_instr.get(t, 0); bc = bytype_base.get(t, 0)
          ib = '█' * max(1 if ic else 0, round(ic / max_c * W))
          bb = '█' * max(1 if bc else 0, round(bc / max_c * W))
          name = (t[-44:] if len(t) > 44 else t)
          lines.append(f"  {name:<46}  instr [{ib:<{W}}]{ic:>5}   base [{bb:<{W}}]{bc:>5}")
      lines.append("```")
      print('\n'.join(lines))
      ```
    - **Flamegraph SVGs** — `<skill-dir>/flamegraph.py` generates interactive SVG flamegraphs directly from the `jfr print` text files (auto-detected format; no separate conversion step). Run for all four combinations:
      ```bash
      SKILL_DIR=<absolute-path-to-skill-dir>
      python3 "$SKILL_DIR/flamegraph.py" <work-dir>/instr_cpu.txt   <work-dir>/instrumented_cpu.svg   --title "Instrumented — CPU samples"
      python3 "$SKILL_DIR/flamegraph.py" <work-dir>/base_cpu.txt    <work-dir>/baseline_cpu.svg       --title "Baseline — CPU samples"
      python3 "$SKILL_DIR/flamegraph.py" <work-dir>/instr_alloc.txt <work-dir>/instrumented_alloc.svg --title "Instrumented — TLAB allocations"
      python3 "$SKILL_DIR/flamegraph.py" <work-dir>/base_alloc.txt  <work-dir>/baseline_alloc.svg     --title "Baseline — TLAB allocations"
      ```
      If `flamegraph.pl` is on PATH, prefer it: convert text → folded stacks (reverse each stack) and pipe through `flamegraph.pl --title "..."`. SVGs open directly in a browser; each frame is clickable to highlight callers. Reference all four SVG paths in `report.md`.

12. **Report**.

    Concise chat summary:
    - **Per-call wall-clock ns** delta (instrumented − baseline), from workload stdout. Headline.
    - **Allocations**: per-bucket totals + top 3 host-class methods (collapsed to method, not full stack).
    - **CPU**: per-bucket sample counts + top 3 host-class methods by sample count.
    - **Class-footprint delta**: bucketed by package prefix; `datadog.trace.{,bootstrap.}instrumentation.<name>.*` count as the headline.
    - **JIT compile delta** (`jdk.Compilation` events) — big delta means instrumentation defeated inlining of hot user code.
    - **`dd-*` thread CPU** if any > 5%.
    - One-line restatement of the limitations preamble.

    Full markdown report at `<work-dir>/report.md` with: inputs, integration names, JDK version, workload source, per-bucket totals (allocation + CPU), top stacks per bucket, ClassLoad-by-package table, per-thread CPU summary, **inlined-advice attribution table** (which `*Instrumentation.java` advice maps to which host-class method), paths to raw JFR files for JMC.

    **Mandatory section "Visualizations"** in `report.md`:
    ```markdown
    ## Visualizations
    | File | Description |
    |---|---|
    | [instr_hotmethods.txt](instr_hotmethods.txt) | `jfr view hot-methods` — instrumented |
    | [base_hotmethods.txt](base_hotmethods.txt) | `jfr view hot-methods` — baseline |
    | [instrumented_cpu.svg](instrumented_cpu.svg) | CPU flamegraph — instrumented (open in browser) |
    | [baseline_cpu.svg](baseline_cpu.svg) | CPU flamegraph — baseline |
    | [instrumented_alloc.svg](instrumented_alloc.svg) | TLAB allocation flamegraph — instrumented |
    | [baseline_alloc.svg](baseline_alloc.svg) | TLAB allocation flamegraph — baseline |

    ### Allocation [B] histogram — instrumented vs baseline
    <embed the ASCII histogram generated in step 11>

    ### Hot methods — instrumented (top 10)
    <embed first 15 lines of instr_hotmethods.txt>
    ```

    **Mandatory section: "Code improvements suggested by this run"** — concrete, integration-scoped optimizations grounded in the run's hot-path data. For each: cite file + line numbers, quote the relevant snippet, propose the fix in 1-2 sentences, name the events/samples motivating it, rank by ROI. Include a brief "Not pursued" subsection for ideas considered but rejected. If no actionable improvements (rare), say so with a one-line explanation. Stay within the integration's scope — agent-core changes are listed but flagged "agent-core, surfacing for context".

    **Also emit `<work-dir>/suggestions.json`** — structured form for `--auto-optimize`. Each entry:
    ```json
    {
      "id": 1,
      "scope": "integration",
      "file": "absolute/path",
      "anchor": "human-readable location",
      "find": "exact code snippet to replace — must be unique in the file",
      "replace": "the proposed replacement",
      "description": "one-line summary",
      "target_frames": {
        "allocation": ["datadog.trace.instrumentation.<name>.HotClass.method"],
        "cpu": []
      }
    }
    ```
    `scope`: `"integration"` (auto-applied) or `"core"` (skipped). `find: null` if no surgical patch exists (multi-method refactor). `target_frames` is required (auto-mode uses it as the primary signal); fully-qualified method names, matched against any frame in an event's stack. Re-use the frames identified in the "Top dd-frames in [B]" tables. If a suggestion has no clear target frame, don't emit it to `suggestions.json` — keep it in the markdown report only, marked "(manual review only — no JFR signal)".

    Print the report path to chat.

13. **Persist run state** to `<work-dir>/run.json` for `--rerun`:
    ```json
    {
      "module": "<gradle-path>",
      "fqcn": "overhead.Workload",
      "workload_java": "<work-dir>/Workload.java",
      "classpath_file": "<work-dir>/classpath.txt",
      "agent_jar": "<absolute-path>",
      "agent_version": "<version>",
      "warmup": 1000,
      "measured": 10000,
      "driver": "h2",
      "integration_names": ["..."]
    }
    ```

    Then ask the user verbatim: *"I generated N integration-scoped suggestions. Want me to try them with `--auto-optimize`? [y/N]"*. Default no. If yes — or if `--auto-optimize` was passed on the invocation — proceed to `auto-optimize.md` (sibling file in this directory).

## Failure modes

- **No matching module** → list `dd-java-agent/instrumentation/*/` directories and ask.
- **No tests in the module** → ask the user for a workload pattern; offer `--workload <path>`.
- **Agent jar missing** after `:dd-java-agent:shadowJar` → surface the Gradle error verbatim.
- **`javac` compile error in workload** → surface verbatim, abort, do not run the JVM.
- **Workload JVM exit ≠ 0** → capture stderr verbatim, surface, **still attempt JFR analysis**.
- **`jfr` CLI not on `PATH`** → tell the user to set `JAVA_HOME` to a full JDK (not JRE), or install JDK 17+. Required for both `jfr print` and `jfr view`.
- **`jfr print` NPE on ClassLoad with stacks (JDK 25+)** → use `--stack-depth 0`.
- **Bucket [B] < 100 events** → not a failure; warn + suggest tuning. Report still produced; per-call numbers labelled "low-confidence".
- **Integration name ambiguity** → ask the user.
- **Host-class set unclear** → list candidate packages from imports + `Instrumenter.typeMatcher()` and ask the user to confirm.
- **`flamegraph.py` produces empty SVG** → input file likely has no stack events (e.g. `measured` too low, or workload exited early). Check `jfr summary` event counts; bump `--measured` or verify workload reaches the hot path.

## Conventions

- Do NOT modify production or test code, build files, or `overhead.jfc`. The skill is pure orchestration except for that file.
- Do NOT commit anything.
- Per-run artifacts go under `/tmp/overhead-...`. Don't write under the repo.
- Keep chat output concise; the markdown report holds the detail.
- Always re-state the limitations preamble at the start of every run, even on `--rerun`.
- **Report only baseline-vs-instrumented for the target integration.** Never add cross-integration comparison tables. Different integrations have different workloads, per-call work, and JIT effects — cross-comparisons are misleading. Same-integration comparisons across methodology iterations (LoggingWriter vs NoOpWriter, iteration counts) are fine — same axis.
- **Auto-mode is non-destructive.** No automatic commits. Reverts use captured file content (`Read` before `Edit`, `Write` to restore) — never `git checkout`. Standard report is never modified; auto-mode produces a separate report under `<work-dir>/auto/`. Full mechanics in `auto-optimize.md`.
- **`flamegraph.py`** is at `<skill-dir>/flamegraph.py` — pure Python 3 stdlib, no pip dependencies. It auto-detects `jfr print` text vs folded-stack format. Always run it; SVG flamegraphs make the bucket analysis immediately visual.

## 4-bucket reference

When attributing JFR events (allocations or CPU samples), place each event in the **first matching bucket** by walking its stack from the innermost frame outward:

- **[A] Setup / bytecode transform / agent install** — `net.bytebuddy.*`, `datadog.trace.agent.tooling.bytebuddy.*`, `datadog.trace.agent.tooling.AgentInstaller*`, `datadog.trace.agent.tooling.AdviceStack*`, `datadog.trace.agent.tooling.muzzle.*`. **Excluded from headline numbers** — install-time cost.
- **[B] Integration-related** — frames matching the integration's host-class set (e.g. for JDBC: `java.sql.*`, `javax.sql.*`, `org.h2.*`, `com.zaxxer.hikari.*`, `test.Test*`) **OR** `datadog.trace.instrumentation.<name>.*` / `datadog.trace.bootstrap.instrumentation.<name>.*` (non-inlined helpers). **The headline bucket.** Inlined advice executes inside host-class methods, so allocations and CPU samples charged to the host class ARE the integration's runtime cost.
- **[C] Tracer one-time initialization** — `datadog.trace.api.Config*`, `datadog.trace.core.CoreTracer.<init>`, `datadog.trace.core.StatusLogger`, `datadog.trace.core.scopemanager.*`, `datadog.trace.bootstrap.Agent`, `datadog.trace.core.datastreams.DefaultDataStreamsMonitoring`. One-time cost regardless of integration.
- **[D] Other agent runtime** — anything else in `datadog.*` / `com.datadog.*`. Examples: `FieldBackedContextStore.weakStore`, `SerializingMetricWriter.<init>`.
- **JFR self-cost (sub-bucket)** — frames in `jdk.jfr.*`. Report separately; subtract from [D] before reporting.
