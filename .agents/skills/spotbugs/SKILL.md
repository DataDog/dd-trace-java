---
name: spotbugs
description: >-
  Enable one mechanically enforceable lint or static-analysis rule in blocking CI,
  preferring SpotBugs. Use when adding a SpotBugs detector, turning a repetitive PR
  review theme into a lint, adding a ForbiddenAPIs signature, or writing a custom
  Gradle source check.
user-invocable: true
context: fork
allowed-tools:
  - Bash
  - Read
  - Grep
  - Glob
  - Edit
  - Write
---

# Add a SpotBugs check

Grow the blocking SpotBugs job **one check at a time**. Do not turn this
into a style-guide dump or a Spotless / google-java-format gate.

Background: [`gradle/spotbugs.gradle`](../../../gradle/spotbugs.gradle),
[`gradle/spotbugFilters/exclude.xml`](../../../gradle/spotbugFilters/exclude.xml),
[detector list](https://spotbugs-in-kengo-toda.readthedocs.io/en/lqc-list-detectors/detectors.html).
Fallbacks: [other vehicles](references/other-vehicles.md).

## This repository

- First-party production sources: every Java module that applies `gradle/java_no_deps.gradle`
- Tasks that may report: `spotbugsMain`, `spotbugsMain_java11`
- Run: `./gradlew spotbugsMain -PskipTests` (optionally qualify with `:module:spotbugsMain`)
- CI: `check_base` / `check_inst` / `check_profiling` / `check_debugger` run Gradle `check`, which includes those tasks

Leave tests, smoke tests (`:dd-smoke-tests`), generated sources, and vendored trees out unless the user explicitly expands the filter.

`omitVisitors` in `gradle/spotbugs.gradle` is the deferred-check list. `exclude.xml` is the path/class filter. `@SuppressFBWarnings` is the local override.

## Hard constraints

- Enable **one** check per change. Prefer removing one name from `omitVisitors` over adding a new detector.
- Do not enable a detector family or bulk-remove `omitVisitors` entries.
- Keep `ignoreFailures = false` on Main tasks. Do not mark GitLab `check_*` jobs `allow_failure`.
- Do not enable SpotBugs on test or smoke-test source sets unless asked.
- Do not widen `exclude.xml`, `onlyAnalyze`, or the Main-only task filter unless asked.
- Do not use `@SuppressFBWarnings` as the way to land a noisy check. Fix the finding, or leave the visitor omitted.
- Do not treat this as a format change. Run `./gradlew spotlessApply` only if you touched Java/Gradle files that Spotless owns.
- Keep `omitVisitors` accurate: a deferred detector belongs in that list, not in a comment elsewhere.

## Workflow

1. Classify the rule. Prefer SpotBugs. Read `gradle/spotbugs.gradle` and the [detector list](https://spotbugs-in-kengo-toda.readthedocs.io/en/lqc-list-detectors/detectors.html).
   - If a visitor in `omitVisitors` matches, that is the check to enable.
   - If a visitor is already absent from `omitVisitors`, the check is already on. Stop.
   - If no SpotBugs detector can express the rule, follow [other vehicles](references/other-vehicles.md). Do not invent a custom SpotBugs detector unless the user asks.
2. Remove **one** visitor name from `omitVisitors`.
3. Run SpotBugs from the repo root:

   ```bash
   ./gradlew spotbugsMain -PskipTests
   ```

   If the rule is scoped to one module, run that module's `spotbugsMain` first, then a broader `spotbugsMain` before finishing.
4. If it fails:
   - Mechanical first-party fixes in `src/main/java` (and `src/main/java11`) are OK in the same change.
   - Do not edit generated, vendored, or smoke-test sources to silence SpotBugs.
   - Do not paper over findings with a blanket `exclude.xml` match or a pile of `@SuppressFBWarnings`.
   - If the cleanup is large or opinionated, put the visitor back in `omitVisitors` (name the failing classes) and stop. Ask before a repo-wide rewrite.
5. Keep the check only if `spotbugsMain` exits 0.

## Local command

```bash
./gradlew spotbugsMain -PskipTests
```

HTML reports: `*/build/reports/spotbugs/spotbugsMain.html`.
Bug codes in those reports (`NP_…`, `DCN_…`) are **not** the `omitVisitors` names. Visitors are detector class names (`FindNullDeref`, `DefaultEncodingDetector`). Suppressions use the bug code:

```java
@SuppressFBWarnings(value = "NP_BOOLEAN_RETURN_NULL", justification = "…")
```
