# Other vehicles (only if SpotBugs cannot express the rule)

Use this file after the [Java lint skill](../SKILL.md) classification step finds no matching detector.
Do not start here.

Pick the **narrowest** vehicle that can fail CI with low false positives. One rule per change, same as SpotBugs.

## ForbiddenAPIs — unconditional banned call or class

Convention: `dd-trace-java.conventions.forbidden-apis`, which applies `de.thetaphi.forbiddenapis`. Locate it by plugin
ID and follow `forbiddenApis.signaturesFiles` to the shared lists; do not add a new signature file.

Two lists exist today:

- `main.txt` - common rules from `dd-trace-java.conventions.forbidden-apis`
- `instrumentation.txt` - extra rules added by `dd-trace-java.module.instrumentation`

Put a repo-wide ban in `main.txt`. Put a ban that should apply only to instrumentation modules in `instrumentation.txt`.
Leave plugin/task enablement in the module configuration.

Use when the rule is “never call this method/class” with **no location qualifier** (not “only in `@Advice`”,
not “only in premain”).

1. Add or update one rule in the matching signature file, including all required overloads.
   - For a new rule, set its own `@defaultMessage` before its signatures, or give each signature an explicit inline
     message.
   - For an existing rule, update its signatures and message in place. Keep added overloads with that rule and check
     which signatures inherit a changed `@defaultMessage`.

   For example, when extending a `System.getenv()` ban to its other overload, keep both signatures under the same
   message:

   ```text
   @defaultMessage Use ConfigHelper to access environment variables.
   java.lang.System#getenv()
   java.lang.System#getenv(java.lang.String)
   ```
2. Preserve task enablement and module overrides. The convention disables Forbidden APIs task names ending in `Test`
   and sets `ignoreFailures = false`. Inspect the affected modules for further overrides.
3. Verify:

   ```bash
   ./gradlew forbiddenApis -PskipTests
   ```

   Confirm that the tasks covering the changed rule ran with `ignoreFailures = false`. Report any coverage gaps from
   disabled tasks or module overrides.

Escape hatch already in the codebase: `@SuppressForbidden`. Do not invent a second one.

## Custom Gradle check — path-scoped or structural

Existing patterns: `dd-trace-java.config-inversion-linter` (`ConfigInversionLinter`) and
`dd-trace-java.instrumentation-naming` (`InstrumentationNamingPlugin`). Find their plugin registrations and follow
the implementation classes and registered tasks. Reuse the existing JavaParser dependency for source checks.

Use when the rule needs source structure or a path filter SpotBugs cannot name, for example:

- only inside `@Advice.OnMethodEnter` / `@Advice.OnMethodExit`
- only under bootstrap / premain
- module-directory or config-inventory conventions

Do **not** add the rule to `ConfigInversionLinter` unless it is about supported configuration. Register a dedicated
verification task and a GitLab job (or attach it to an existing `check*` aggregator) so it is a merge gate.

Verify with the new task, then `./gradlew spotlessApply` on the Gradle/Kotlin files you touched.

## Custom SpotBugs detector

Do not write one unless the user explicitly asks **and** ForbiddenAPIs plus a Gradle source check are both a poor fit.
Custom detectors are bytecode visitors with `findbugs.xml` / `messages.xml` plugin wiring; they are a poor default
agent output.

## Do not use

| Vehicle | Why |
|---|---|
| Spotless | Formatter, not a review-theme lint |
| CodeNarc | Groovy-only; new Groovy is already blocked |
| ErrorProne / NullAway | Wired only in `:dd-java-agent:agent-iast` |
| `static-analysis.datadog.yml` | No in-repo CI job; not a required check |
| CodeQL / Trivy | Security scanners; not PR merge gates |
| One-off GitHub Action scripts | Fine for PR metadata, not for source conventions |
