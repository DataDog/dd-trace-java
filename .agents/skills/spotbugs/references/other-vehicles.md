# Other vehicles (only if SpotBugs cannot express the rule)

Use this file after the [SpotBugs skill](../SKILL.md) classification step finds no matching detector. Do not start here.

Pick the **narrowest** vehicle that can fail CI with low false positives. One rule per change, same as SpotBugs.

## ForbiddenAPIs — unconditional banned call or class

Config: [`gradle/forbiddenApiFilters/main.txt`](../../../../gradle/forbiddenApiFilters/main.txt),
[`gradle/forbiddenapis.gradle`](../../../../gradle/forbiddenapis.gradle).

Use when the rule is “never call this method/class” with **no location qualifier** (not “only in `@Advice`”, not “only in premain”).

1. Add or update one rule in the appropriate shared signature file, including all required overloads.
   - For a new rule, set its own `@defaultMessage` before its signatures, or give each signature an explicit inline message.
   - For an existing rule, update its signatures and message in place. Keep added overloads with that rule and check which signatures inherit a changed `@defaultMessage`.

   For example, when extending a `System.getenv()` ban to its other overload, keep both signatures under the same message:

   ```text
   @defaultMessage Use ConfigHelper to access environment variables.
   java.lang.System#getenv()
   java.lang.System#getenv(java.lang.String)
   ```
2. Preserve existing task enablement (they are disabled on purpose).
3. Verify:

   ```bash
   ./gradlew forbiddenApis -PskipTests
   ```

Escape hatch already in the codebase: `@SuppressForbidden`. Do not invent a second one.

## Custom Gradle check — path-scoped or structural

Existing pattern: [`ConfigInversionLinter`](../../../../buildSrc/src/main/kotlin/datadog/gradle/plugin/config/ConfigInversionLinter.kt),
[`InstrumentationNamingPlugin`](../../../../buildSrc/src/main/kotlin/datadog/gradle/plugin/naming/InstrumentationNamingPlugin.kt).
JavaParser is already a `buildSrc` dependency.

Use when the rule needs source structure or a path filter SpotBugs cannot name, for example:

- only inside `@Advice.OnMethodEnter` / `@Advice.OnMethodExit`
- only under bootstrap / premain
- module-directory or config-inventory conventions

Do **not** add the rule to `ConfigInversionLinter` unless it is about supported configuration. Register a dedicated verification task and a GitLab job (or attach it to an existing `check*` aggregator) so it is a merge gate.

Verify with the new task, then `./gradlew spotlessApply` on the Gradle/Kotlin files you touched.

## Custom SpotBugs detector

Do not write one unless the user explicitly asks **and** ForbiddenAPIs plus a Gradle source check are both a poor fit. Custom detectors are bytecode visitors with `findbugs.xml` / `messages.xml` plugin wiring; they are a poor default agent output.

## Do not use

| Vehicle | Why |
|---|---|
| Spotless | Formatter, not a review-theme lint |
| CodeNarc | Groovy-only; new Groovy is already blocked |
| ErrorProne / NullAway | Wired only in `:dd-java-agent:agent-iast` |
| `static-analysis.datadog.yml` | No in-repo CI job; not a required check |
| CodeQL / Trivy | Security scanners; not PR merge gates |
| One-off GitHub Action scripts | Fine for PR metadata, not for source conventions |
