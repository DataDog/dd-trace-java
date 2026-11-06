---
name: java-lint
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

# Add a Java lint rule

Grow the blocking SpotBugs job **one check at a time**. Do not turn this
into a style-guide dump or a Spotless / google-java-format gate.

Locate build logic by plugin ID. Search both filenames and plugin registrations, then follow applied plugins and
implementation classes.

Background: [SpotBugs detector list](https://spotbugs.readthedocs.io/en/stable/detectors.html).
Fallbacks: [other vehicles](references/other-vehicles.md).

## This repository

- Convention: `dd-trace-java.conventions.spotbugs`, which applies `com.github.spotbugs`
- Production sources: `main` and `main_java<N>` (`N` is the Java language level) in modules using that convention,
  subject to shared filters and module overrides
- Enabled task names: `spotbugsMain` and `spotbugsMain_java<N>`; tests, benchmarks and smoke-test modules are disabled
  by the convention
- Run: `./gradlew check -PskipTests` includes all enabled SpotBugs tasks and other checks
- CI: `check_base` / `check_inst` / `check_profiling` / `check_debugger` run Gradle `check`, which includes those tasks

Keep tests, smoke tests (`:dd-smoke-tests`), generated sources, and vendored trees out unless the user explicitly
expands the filter.

`omitVisitors` in the SpotBugs convention is the deferred-check list. Follow its `excludeFilter` property to the shared
path/class filter. `@SuppressFBWarnings` is the local override.

## Hard constraints

- Enable **one** check per change. Prefer removing one name from `omitVisitors` over adding a new detector.
- Do not enable a detector family or bulk-remove `omitVisitors` entries.
- Keep the convention's `ignoreFailures = false` for production tasks. Do not mark GitLab `check_*` jobs
  `allow_failure`.
- Do not enable SpotBugs on test or smoke-test source sets unless asked.
- Production source sets include `main` and version-specific sets such as `main_java11`, `main_java17`, `main_java21`
  and `main_java25`, etc. Check the shared SpotBugs convention for the enabled sets and report any coverage gaps.
- Do not widen the shared exclusion filter, `onlyAnalyze`, or the production-source task filter unless asked.
- Do not use `@SuppressFBWarnings` as the way to land a noisy check. If the detector is too noisy, leave the visitor
  omitted. For an intentional local exception, keep the detector enabled and annotate that site with
  `@SuppressFBWarnings(value = "…", justification = "…")`.
- Do not treat this as a format change. Run `./gradlew spotlessApply` only if you touched Java/Gradle files that
  Spotless owns.
- Keep `omitVisitors` accurate: a deferred detector belongs in that list, not in a comment elsewhere.

## Workflow

1. Classify the rule. Prefer SpotBugs. Read the SpotBugs convention and the
   [detector list](https://spotbugs.readthedocs.io/en/stable/detectors.html).
   - If a visitor in `omitVisitors` matches, that is the check to enable.
   - If a matching visitor is absent from `omitVisitors`, check its defaults in the SpotBugs version in use and the
     task configuration. Stop only if the detector is already enabled.
   - If no SpotBugs detector can express the rule, follow [other vehicles](references/other-vehicles.md). Do not invent
     a custom SpotBugs detector unless the user asks.
2. Enable one visitor in the shared SpotBugs convention. Remove it from `omitVisitors` if present, or explicitly enable
   it if it is disabled by default. Review all bug patterns it reports and use `@SuppressFBWarnings` with a
   justification for intentional exceptions.
3. Run all enabled production SpotBugs tasks. From the repo root, `check` includes them along with other checks:

   ```bash
   ./gradlew check -PskipTests
   ```

   For SpotBugs only, list tasks with `./gradlew tasks --all`, then run `spotbugsMain` and all enabled
   `spotbugsMain_java<N>` tasks.
   If the rule targets one module, check it first, then all modules.
4. If it fails:
   - Mechanical first-party fixes in the enabled production source sets are OK in the same change. Read their source
     directories from the module configuration.
   - Do not edit generated, vendored, or smoke-test sources to silence SpotBugs.
   - Do not paper over findings with a blanket exclusion-filter match. A justified `@SuppressFBWarnings` on an
     intentional exception is OK; a pile of suppressions to make a noisy detector pass is not.
   - If the cleanup is large or opinionated, undo this change's visitor activation and stop. Report the failing
     classes and the changes they need to the main assistant, so it can ask the user before starting a repo-wide
     rewrite.
5. Keep the check only if all selected analysis tasks succeed. Confirm that the tasks covering the changed code ran
   with `ignoreFailures = false`.

## Reports

Find HTML report locations in each `SpotBugsTask`'s `reports` configuration.
Bug codes in those reports (`NP_…`, `DCN_…`) are **not** the `omitVisitors` names. Visitors are detector class names
(`FindNullDeref`, `DefaultEncodingDetector`). Suppressions use the bug code:

```java
@SuppressFBWarnings(value = "NP_BOOLEAN_RETURN_NULL", justification = "…")
```
