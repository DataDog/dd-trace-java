# Retry Marker Plan — v2 (optimized)

> Supersedes [`retry-marker-plan.md`](retry-marker-plan.md). Same goal, corrected design.
> Motivated by the failure of CI pipeline `115477619` (commit `0c0e4b57`, branch
> `mo.atie/apmlp-1297-skip-on-retried`): **98 of 377 tasks failed**. This document records the
> root causes and proposes a design with a smaller blast radius.

## Goal (unchanged)

For any retried test, all attempts **except the last** get `dd_tags[test.final_status] = skip` via
the existing `addFinalStatusProperty` mechanism in `JUnitReport.java`. The last attempt keeps its
natural outcome (`pass` or `fail`).

## Why v1 broke CI — three independent defects, one PR

All three were introduced by the v1 implementation. Two are still present in the working tree.

### Defect 1 — forbiddenApis violation (fails `check_base`)

`RetryMarkerListener` logs with `System.err.println` (line 51, and the same pattern in v1's
listing). The repo's forbiddenApis policy bans `System.out`/`System.err`:

```
Forbidden field access: java.lang.System#err
  [Avoid using System.out/err to prevent excess logging. To override, add @SuppressForbidden.]
  in datadog.trace.junit.utils.retry.RetryMarkerListener (RetryMarkerListener.java:51)
Execution failed for task ':utils:junit-utils:forbiddenApisMain'.
```

Because `:utils:junit-utils` is a foundational module, this single check failure is enough to red
the `check_base` job. **Status: still present in the working tree.**

### Defect 2 — `dd.` system property collides with strict test hygiene (fails every test shard)

v1 file-edit #4 adds, to *every* `Test` task:

```kotlin
systemProperty("dd.test.results.dir", reports.junitXml.outputLocation.get().asFile.absolutePath)
```

`utils/test-utils/.../DDSpecification.groovy` — the base spec nearly every Groovy/Spock test
extends — asserts in `setupSpec()` that no `dd.*` system property exists outside a tiny allow-list:

```groovy
// DDSpecification.groovy:122
assert systemPropertiesExceptAllowed().findAll { it.key.toString().startsWith("dd.") }.isEmpty()
// allow-list: dd.appsec.enabled, dd.iast.enabled, dd.integration.grizzly-filterchain.enabled
```

`dd.test.results.dir` is not allow-listed, so `setupSpec()` throws, surfacing as
`initializationError` (with `ConditionNotSatisfiedError at DDSpecification.groovy:122`) on **every
spec in every module**. That is why the failures are uniform and repo-wide rather than a handful of
real product regressions — e.g. `agent_integration_tests` shows `12 of 13` specs failing with the
identical `initializationError`, and every `test_base`/`test_inst`/`test_inst_latest` shard reports
`There were failing tests` across unrelated modules.

This is not just an allow-list miss. The `dd.` prefix is the agent's **product configuration**
namespace (`Config` strips `dd.` and would interpret `dd.test.results.dir` as a config key). Test
infrastructure must not write into it. **Status: still present in the working tree.**

### Defect 3 — missing import breaks the result collector (non-fatal, silent data loss)

The committed `JUnitReport.java` used `new LinkedHashSet<>()` without `import
java.util.LinkedHashSet`, so the after-script `collect_results.sh` failed to compile:

```
.gitlab/collect-result/JUnitReport.java:129: error: cannot find symbol  ... class LinkedHashSet
```

This runs in `after_script`, so GitLab reports *"after_script failed, but job will continue
unaffected"* — the job's red status comes from defects 1–2, **but the result/retry-marker
collection silently produces nothing** (`results/*.xml: no matching files`,
`test_counts_*.json: no matching files`). A feature about test-result tagging that fails closed and
quietly is worse than one that fails loud. **Status: fixed in the working tree** (import added); the
underlying fragility — `.gitlab/collect-result/*.java` is run as source with no compile gate — is
not.

## Root design problem

v1 has three pieces of **repo-global** blast radius:

1. A `TestExecutionListener` auto-registered via SPI in the `junit-utils` *main* jar → loaded into
   every test JVM in the repository.
2. A system property set on every `Test` task, in the one namespace the test harness actively
   polices.
3. Marker XML files parsed by `ResultCollector`.

Piece (1) is acceptable on its own — the listener already no-ops when the output property is unset.
The damage came from pieces (2) and (3) plus the forbiddenApis slip. The optimization is to shrink
blast radius and stop reusing the `dd.` namespace.

## Recommended design

Two tracks. Track A is the immediate unblock (low risk, do now). Track B is the simplification worth
landing if a one-day spike confirms it.

### Track A — corrected minimal design (P0, unblocks CI)

1. **Remove the forbidden logging.** The catch blocks are best-effort. Either drop the
   `System.err.println` entirely (preferred — a retry-marker write failure should not be noisy), or
   annotate the method with `@SuppressForbidden` if a diagnostic is genuinely wanted. Do **not**
   reintroduce `System.out`/`System.err`.

2. **Move the property out of the `dd.` namespace.** Rename `dd.test.results.dir` →
   `datadog.test.results.dir` (does not start with `dd.`, so `DDSpecification` ignores it, and
   `Config` does not read it). Update the constant in `RetryMarkerListener`
   (`OUTPUT_DIR_PROP`) to match. This is the correct fix, not a band-aid: the value is
   test-infrastructure metadata, not agent configuration.
   - *If* a `dd.`-prefixed name is required for some reason, the fallback is to add it to
     `DDSpecification`'s allow-list in **both** `setupSpec()`/`cleanupSpec()` paths — but prefer the
     rename.

3. **Make the result collector compile-safe.** Keep the import fix, and add a compile gate so a
   missing import can never again pass review and fail silently in `after_script`. Either compile
   `.gitlab/collect-result/*.java` in a fast `check_base`-adjacent step, or add a tiny JUnit/unit
   test that runs `ResultCollector` over a fixture directory containing a `TEST-retried-*.xml`
   marker and asserts the `skip` tagging. The latter also locks in the feature's behavior.

4. **Re-run the pipeline on a commit that contains 1–3** and confirm `check_base` and a sample of
   `test_base`/`test_inst` shards go green before merge.

### Track B — listener-free simplification (recommended, gated by a spike)

The listener + system property + marker files exist to answer one question: *which `classname#name`
entries are non-final retry attempts?* Gradle's Develocity test-retry plugin re-runs failed tests,
and retries of the same test produce **additional `<testcase>` elements with an identical
`classname#name`** in the JUnit XML. If those attempts are retained in the XML the collector already
reads, then retries can be detected by **duplicate-counting inside `ResultCollector`** — with no
listener, no system property, and no marker files. All three v1 failure modes disappear by
construction.

Crucially, v1's stated objection (name normalization collapses `localhost:12345` and
`localhost:23456` to `localhost:PORT`, so post-normalization matching over-skips) does **not** apply
here: duplicate detection runs on the **raw, pre-normalization** names within a single suite file,
where two genuinely distinct tests have distinct names and only true retries collide. The existing
ordering (`tagRetriedTests` before `normalizeStableTestNames`) is preserved.

```
collect(sourceXml):
  report = parse(sourceXml)
  report.tagRetriedAttempts()        // NEW: tag all but the last of each duplicated classname#name as skip
  report.normalizeStableTestNames()
  report.tagSyntheticFailures()
  report.tagFinalStatuses()
```

**Spike to de-risk before committing to Track B (≈ half a day):** pick one reliably-flaky module,
force a retry locally with `develocity.testRetry { maxRetries = 1 }`, and inspect the generated
JUnit XML (`build/test-results/test/*.xml`), including the `forkEvery = 1` forked-test path. Confirm
the XML contains one `<testcase>` per attempt. If it does, delete the listener, the SPI registration,
the `junit-platform-launcher` dependency, the system property, and the marker-file plumbing, keeping
only `tagRetriedAttempts()` + its test. If the plugin keeps only the final attempt in the XML, stay
on Track A's corrected listener design.

## Files

### Track A
- EDIT `utils/junit-utils/src/main/java/.../retry/RetryMarkerListener.java` — remove `System.err`
  usage; rename `OUTPUT_DIR_PROP` to `datadog.test.results.dir`.
- EDIT `buildSrc/src/main/kotlin/dd-trace-java.configure-tests.gradle.kts` — rename the
  `systemProperty` key to `datadog.test.results.dir`.
- EDIT `.gitlab/collect-result/JUnitReport.java` — keep `import java.util.LinkedHashSet;` (and any
  other missing imports); prefer real imports over fully-qualified names so the compile gate catches
  regressions.
- NEW test/compile gate for `.gitlab/collect-result/*.java`.

### Track B (if the spike passes) — in addition to Track A's collector test
- EDIT `.gitlab/collect-result/JUnitReport.java` — add `tagRetriedAttempts()` (duplicate-based);
  remove `testcaseKeys()`/`tagRetriedTests(Set)` if no longer used.
- EDIT `.gitlab/collect-result/ResultCollector.java` — drop `applyRetryMarkers`/marker-file scan;
  call `tagRetriedAttempts()`.
- DELETE `RetryMarkerListener.java`, its `META-INF/services` registration, the
  `compileOnly(libs.junit.platform.launcher)` line, and the `datadog.test.results.dir`
  system property.

## Examples (unchanged semantics)

**Flaky (retried → passed):** attempt 1 → `skip`, attempt 2 → `pass`.
**Always failing (retried → still fails):** attempts 1–2 → `skip`, attempt 3 → `fail`.

## Track B — implementation status (branch `mo.atie/apmlp-1297-retry-marker-v2`)

Implemented directly on top of clean `master` (no v1 retry-marker work present), so Track B reduced
to two edits — the listener, its SPI registration, the `junit-platform-launcher` dependency, the
`datadog.test.results.dir` system property, and the marker-file plumbing never needed to exist:

- `JUnitReport.tagRetriedAttempts()` — groups `<testcase>` by raw `classname#name` and tags every
  attempt except the last (document order) as `skip` via the existing `addFinalStatusProperty` path.
- `ResultCollector.collect()` — calls `tagRetriedAttempts()` after `addFileAttribute` and before
  `normalizeStableTestNames()`, matching the documented call order.

Verified locally: `javac .gitlab/collect-result/*.java` compiles. (Runtime uses the Java 25
single-file/multi-file source launcher via `collect_results.sh`; local default JDK is 21, so the
launcher path itself is exercised only in CI.)

### Remaining verification gate (CI-only)

The design's one external assumption is that Gradle's Develocity test-retry plugin appends **every**
attempt of a retried test to the same per-class JUnit XML as duplicate `<testcase>` elements with an
identical `classname#name`. This is consistent with the v1 plan's own observed XML examples and the
plugin's documented behavior, but should be confirmed on a real pipeline run before merge: trigger a
retry (a known-flaky module under `CI=true`, where `maxRetries=3` is active) and confirm the
collected `results/*.xml` shows the earlier attempts tagged `skip` and only the final attempt
carrying its natural pass/fail status. If the plugin ever keeps only the final attempt in the XML,
fall back to the corrected listener design (Track A of this document).
