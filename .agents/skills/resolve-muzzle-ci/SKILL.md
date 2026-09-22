---
name: resolve-muzzle-ci
description: >
  Diagnose and resolve dd-trace-java CI failures from a module's muzzle task or the runMuzzle
  aggregate. Use when CI names muzzle/runMuzzle, reports Muzzle validation or version-range
  resolution failures, or starts failing after a newly published library version. Distinguishes
  transient repository or MagicMirror failures from actionable compatibility, artifact, and
  validation-JDK defects; do not use for unrelated Gradle dependency or test failures.
---

# Resolve muzzle CI

Treat the failing task and its first causal exception as the routing evidence. A failed job that
happens to download dependencies is not necessarily a muzzle failure.

## Confirm and chime in

1. Read the CI job log far enough to identify the full Gradle task path and first causal exception,
   not only the final `Muzzle validation failed` wrapper.
2. Confirm that the task is a module `:muzzle*` task or the `runMuzzle` aggregate, or that the stack
   is from `datadog.gradle.plugin.muzzle` / `MuzzleVersionScanPlugin`.
3. As soon as it is confirmed, tell the user briefly:

   ```text
   This is a muzzle CI failure in <task/module>. I am checking whether it is repository
   infrastructure, an artifact/JDK issue, or a real compatibility regression.
   ```

   If it is not a muzzle failure, say which task actually failed and stop using this skill.

Before changing a build file, read:

- `docs/how_to_work_with_gradle.md`
- `docs/how_instrumentations_work.md`, especially its Muzzle and JApiCmp sections
- `.agents/skills/apm-integrations/references/muzzle.md`
- the affected module's `build.gradle` and adjacent versioned modules
- [failure signatures and remedies](references/failure-signatures-and-remedies.md)

## Classify before editing

Preserve the complete relevant log and CI URL. Record the affected task, module, directive,
artifact coordinates, tested version, repository host, and first causal exception.

Classify the failure as exactly one of:

- **Transient platform/repository failure**: timeouts, connection resets, TLS/DNS failures, HTTP
  429/5xx, incomplete metadata from MagicMirror/Depot, or several unrelated artifacts failing to
  download. There is no concrete muzzle mismatch for an already resolved application classpath.
- **Defective or unavailable published artifact**: the same version or one of its transitive
  coordinates is persistently absent, has a broken POM/archive, or cannot form a valid classpath,
  including when checked outside the transient proxy path.
- **Compatibility regression**: muzzle resolved the application classpath and reports missing or
  changed classes, methods, fields, flags, or a class-loader mismatch.
- **Validation-JDK mismatch**: `UnsupportedClassVersionError` occurs while loading a resolved root
  or transitive library class that requires a newer JDK than the Muzzle worker.
- **Declared-failure mismatch**: `MUZZLE PASSED ... BUT FAILURE WAS EXPECTED` shows that a `fail`
  range or inverse expectation is false.
- **Helper injection failure**: `FAILED HELPER INJECTION` shows incomplete, misordered, invisible,
  or incompatible helpers.
- **Muzzle tooling/JDK failure**: worker, toolchain, module-access, bytecode-parser, or JVM failure;
  fix the tooling path without changing the library compatibility range.
- **Unresolved**: the available log is truncated or contradictory. Gather the missing evidence; do
  not edit a version range merely to make CI green.

For a transient platform failure, do not change repository, version, skip, or exclusion settings.
The muzzle resolver already retries range resolution with backoff. Retry the failed job once when
authorized, then report an infrastructure incident if the repository remains unhealthy. Stop here;
do not create a Jira ticket from this workflow.

For the other classifications, reproduce the smallest task with diagnostic output:

```bash
./gradlew :dd-java-agent:instrumentation:<path>:muzzle --stacktrace --info --rerun-tasks
```

Large ranges are reduced before execution, so a green rerun is not proof that a deterministic
mid-range failure disappeared. Rerun the module `muzzle` task and confirm from its output or report
that the exact generated version task ran. If necessary, temporarily narrow a local diagnostic copy
of the directive to `versions = "[<exact-version>]"`; never commit that diagnostic narrowing.

## Choose the smallest honest remedy

Choose directly from the observed cause and prove the remedy against the current artifact and API
evidence.

The major remedies are:

- Fix the instrumentation when the new release is intended to remain supported and a bounded code
  change can restore compatibility. Add or update version-specific tests.
- Cap the pass range at the first incompatible version when that version introduces a real API or
  linkage boundary that this module does not support. Check whether a sibling module should take
  over; do not `skipVersions` around an ongoing incompatible line.
- Add `skipVersions` only for isolated bad releases whose own POM, artifact, or version metadata is
  defective and where later releases can still be supported.
- Add `excludeDependency 'group:module'` only for a transitive dependency that is not referenced by
  the advice, helpers, matcher requirements, explicit muzzle references, or class-loader matcher.
  Prefer an exact coordinate; use a group wildcard only when the entire group is proven irrelevant.
- Add an exact `extraDependency` only when it belongs to the library's valid runtime/application
  classpath but is not present in the resolved graph.
- Ensure a narrowly scoped Gradle repository entry exists when a valid target artifact or required
  application dependency is intentionally hosted outside the configured repositories. Add
  `extraRepository` to the Muzzle directive when version discovery also requires that repository.
  `extraRepository` configures Aether version discovery; it does not configure Gradle dependency
  resolution.
- Correct the target artifact/module when the directive follows a relocated or obsolete coordinate.
- Set per-directive `javaVersion = "<N>"` when a valid resolved dependency requires that JDK to
  load. Split at a known JDK boundary when useful. Generated `assertInverse` directives do not inherit
  `javaVersion`; if an inverse check requires another JDK, replace the generated inverse with explicit
  `fail` directives that select suitable JDKs and preserve coverage outside the supported range.
  Verify the original failing version and both sides of each boundary.
- Change the expected compatibility outcome of a `fail` block or `assertInverse` only when
  `MUZZLE PASSED ... BUT FAILURE WAS EXPECTED` demonstrates that the expectation is false. Replacing
  generated inverses with equivalent explicit checks to select a suitable JDK must preserve the
  expected outcomes and version coverage.
- Repair helper completeness, ordering, linkage, or visibility for `FAILED HELPER INJECTION`; cap a
  range only when the helper failure proves a real target-library compatibility boundary.
- Repair Muzzle tooling or CI toolchain provisioning for worker, parser, or JVM failures; do not
  change the dependency support range.

Never broaden support, remove an inverse assertion, or exclude a dependency solely because it makes
the task pass. `javaVersion` changes only the worker JDK; it does not fix library linkage or change
the agent, test, or bytecode baselines. Muzzle does not prove that a type named only in a matcher
exists; use an explicit muzzle reference or runtime test when that fact matters.

For a symbol mismatch, run the module's `printReferences` task to trace the generated reference and
compare the last passing and first failing artifacts when useful:

```bash
./gradlew :dd-java-agent:instrumentation:<path>:printReferences
./gradlew japicmp -Partifact=<group>:<module> -Pbaseline=<last-pass> -Ptarget=<first-fail>
```

Muzzle validates binary references and helper injection, not behavior. If code or supported runtime
semantics change, add a behavior test and exercise a coherent library dependency stack.

## Leave the reason beside the workaround

When a fix changes Muzzle configuration or adds a build-file workaround, leave a brief comment beside
the affected directive or setting explaining the concrete failure and compatibility consequence.
Include a removal condition or upstream issue when the workaround is temporary.

For a source-only fix, keep any necessary explanation beside the relevant code or regression test;
no `build.gradle` edit is required. Record the affected version and changed linkage or runtime
assumption where useful. Avoid comments that only say `fix muzzle`, `CI failure`, or `broken version`.

## Verify

Rerun the module `muzzle` task and confirm that the exact failing generated version task ran. Then
run the affected module's relevant tests when executable code, helper requirements, or claimed
support changed. For `javaVersion`, confirm the selected worker JDK and test both sides of any split.
Inspect the diff, then report the exact commands and results; do not call an infrastructure-only
retry a code validation.

## Hand off the follow-up in Jira

Create a Jira follow-up only when muzzle exposes a compatibility gap in a newly published target
library version and the completed change leaves that version unsupported, normally by capping the
current module below it. The ticket tracks restoring support for that new version. Do not create a
ticket for transient platform/repository failures, defective or missing publications, irrelevant
transitive artifacts, tooling/JDK failures, stale inverse assertions, or a change that already
restores and verifies support.

For a qualifying new-version gap, read [the Jira handoff](references/jira-handoff.md) after the
analysis and local verification are complete. If the current request authorizes Jira creation,
create the ticket. If it does not, ask whether the user wants the gap logged and, if so, which Jira
project/board to use; continue safe local work while waiting. Do not guess the destination or claim
the ticket was created when no Jira integration is available.

Finish with the classification, root cause, resolution and its compatibility consequence, validation, any
owner or removal condition, and—only for a qualifying new-version support gap—the Jira disposition
(key/link, copy-ready draft, declined, or not requested).
