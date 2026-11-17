# Working in buildSrc

Use `gradle-best-practices` before changing Gradle code here. If it is missing or incomplete,
follow the [skill setup](../docs/how_to_work_with_gradle.md#gradle-skills) before editing.

For new conventions, follow the [build logic placement advice](../docs/how_to_work_with_gradle.md#build-logic-location).

The Java 8 target in `build.gradle.kts` is needed by build-time instrumentation and Muzzle
workers. A newer Gradle daemon or compiler JVM does not remove that requirement.

JMH uses the consuming build's plugin classpath. Read the comments in
[its convention](src/main/kotlin/dd-trace-java.jmh-conventions.gradle.kts) before changing
that integration or adding JMH to `implementation`.

## Tests

The plugin tests use JUnit 5 and Kotlin. Reuse
[GradleFixture](src/test/kotlin/datadog/gradle/plugin/GradleFixture.kt) for TestKit tests.
CLI tests need `-PrunBuildSrcTests`; see [the test commands](../docs/how_to_work_with_gradle.md#running-build-logic-tests).
Keep the `:test` selector when filtering so the filter does not reach unrelated subprojects.

Changes to `call-site-instrumentation-plugin` or `modifiable-config-agent` also need their
own subproject tests. Run `./gradlew -p buildSrc spotlessCheck` for formatting.
