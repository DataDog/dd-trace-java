# Agent context for buildSrc

Read the [root Gradle instructions](../AGENTS.md#gradle-changes), including the mandatory
`gradle-best-practices` prerequisite, before changing build behaviour. Use the relevant
sections of the [Gradle guide](../docs/how_to_work_with_gradle.md) for explanations.

## Scope and boundaries

`buildSrc` is built automatically before the main build. It contains binary plugins,
precompiled conventions, shared types, and worker support, with subprojects for call-site
instrumentation and the modifiable-config agent.

- Prefer `build-logic` for new project-facing conventions unless a shared-classloader
  requirement justifies `buildSrc`. Follow existing placement for changes to existing plugins.
- Preserve Java 8 compatibility for build-time instrumentation and Muzzle workers. The Gradle
  daemon JVM, compiler JVM, bytecode target, and worker JVM are separate constraints.
- Preserve plugin classloader boundaries. Do not add an optional external plugin to
  `implementation` merely to make typed imports compile. Keep documented integrations such
  as [JMH](src/main/kotlin/dd-trace-java.jmh-conventions.gradle.kts) working on the consumer's
  plugin classpath.
- Sharing this source root does not justify coupling plugin implementations. Follow the
  root guardrails for explicit dependencies and narrow public contracts.
- Check Groovy and Kotlin consumers when changing extension or convention APIs. Keep
  existing defaults, repository proxies, and worker/toolchain selection working.
- Treat settings, repositories, and dependency declarations as belonging to this build.
  Import the shared version catalog through the existing settings configuration.

## Verification

Use existing JUnit 5 tests and Kotlin fixtures for plugin behaviour. Reuse
[GradleFixture](src/test/kotlin/datadog/gradle/plugin/GradleFixture.kt) for TestKit coverage;
keep language migrations outside the requested change. Check integrations with the involved
plugins applied independently and together where applicable.

CLI tests require `-PrunBuildSrcTests`; a successful build with skipped tests is insufficient.
Use the fully qualified `:test` selector when filtering tests, so the filter does not reach
unrelated subprojects:

```shell
./gradlew -p buildSrc :test -PrunBuildSrcTests --tests '*ChangedPluginTest'
./gradlew -p buildSrc spotlessCheck
```

Replace the example test filter with the affected test class. Run the affected subproject's
own tests when changing call-site instrumentation or the modifiable-config agent. Verify a
representative consuming module for changes to shared conventions, then inspect the diff
and run `git diff --check`.
