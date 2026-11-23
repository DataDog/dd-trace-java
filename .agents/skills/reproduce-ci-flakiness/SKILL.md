---
name: reproduce-ci-flakiness
description: >
  Reproduce timing-sensitive dd-trace-java CI test failures locally using bounded reruns,
  interpreted JVM execution (-Xint), and reduced CPU availability. Use for intermittent
  timeouts, asynchronous assertions, races, and tests that pass locally but fail in CI.
  Excludes muzzle compatibility failures and CI retry/history analysis alone.
---

# Reproduce CI flakiness

Read [the reproduction guide](../../../docs/how_to_test.md#reproducing-timing-sensitive-ci-failures)
before running experiments. It contains the commands, flag scopes, and evidence checklist.
The goal is a small local reproducer with the same failure as CI, not merely any failure under load.

## Establish the target

- Extract the commit, full Gradle task path, test class/method, first causal failure,
  JDK version/vendor, architecture, and relevant services from the CI log or artifacts.
  Inspect the module's build file for the actual suite and JVM constraints. Distinguish
  `test`, `forkedTest`, `latestDepTest`, and custom suites; do not guess a task from the class name.
- Retrieve CI logs and artifacts using available authenticated tools and the repository's
  documented access guidance. If access is unavailable, ask for the relevant failure log
  or artifacts. Never request or print access tokens.
- Run the narrowest matching test with the CI JDK and dependencies before adding stress.
  Preserve CI's exact `testJvm` selector and Gradle concurrency settings in the baseline;
  use `JAVA_<selector>_HOME` to point a named selector at the matching local JDK.
  Remove `skipFlakyTests` when targeting an annotated test. Use `runFlakyTests` only when
  intentionally selecting annotated tests. Confirm the test actually executes rather than
  being skipped, filtered out, up to date, or restored from cache.
- Separate dependency resolution, missing services, unsupported JVM flags, and compilation
  failures from test failures. Route muzzle failures to `resolve-muzzle-ci`; route continuation
  lifecycle diagnostics to `fix-continuation-leakage` after preserving the reproduction evidence.

## Perturb timing deliberately

Run a small matrix: baseline, `-XX:ActiveProcessorCount=2` (then `1` if useful), `-Xint`,
then both together. Change one factor at a time and record results per profile. Start with
one attempt per profile; repeat a promising profile up to ten times, with an overall time
budget stated before execution (default 15 minutes). Stop at the first matching failure or
the budget. Do not start another attempt when the remaining budget cannot accommodate it.

Use the guide's command-scoped `JAVA_TOOL_OPTIONS` recipe and verify the failing JVM receives
the flags. This also slows newly launched build JVMs and inherited child processes. If that
prevents useful experiments, use a temporary init script to scope `jvmArgs` to the selected
Gradle `Test` task, and verify child JVMs separately. Do not commit diagnostic settings or
change global Gradle/JDK configuration.

`ActiveProcessorCount` changes JVM-visible CPU count and pool sizing; it is not a CPU quota.
`--max-workers` limits Gradle work, not application threads. If CI starvation is the suspected
cause, use an existing suitable Linux/container environment with a CPU quota or affinity,
recording that as a separate experiment. Reducing Gradle workers can hide contention, so
keep CI's concurrency in the baseline and treat serialized execution as a separate profile.

`-Xint` removes JIT compilation and can hide compiler-dependent races or cause unrelated
timeouts. A different failure is a new observation, not a successful reproduction. Keep a
normal-JIT comparison; passing stress runs do not establish that a test is reliable.

## Preserve and report

Keep the first matching failure's log and JUnit XML/HTML before another invocation overwrites
them. Report the exact command and environment, test/JDK/commit, attempts and failures per
profile, elapsed time, report paths, and whether the signature matches CI. A build failure
or zero executed tests is not a passing attempt.

For hangs, capture thread dumps from the test worker and relevant child JVMs before terminating
them. Do not leave detached retry loops or test processes running when the investigation ends.
Do not retry remote CI, quarantine tests, increase timeouts, or change production code merely
to make reproduction green. Fix requests may proceed to a causal fix; reproduction-only requests
end with evidence and a hypothesis. Validate any subsequent fix under both the reproducing
profile and normal execution.
