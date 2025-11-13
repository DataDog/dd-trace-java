import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.testing.base.TestingExtension
import org.gradle.api.plugins.jvm.JvmTestSuite
import java.time.Duration
import java.time.temporal.ChronoUnit

// Need concrete implementation of BuildService in Kotlin
abstract class ForkedTestLimit : BuildService<BuildServiceParameters.None>
// Forked tests will fail with OOM if the memory is set too high. Gitlab allows at least a limit of 3.
val forkedTestsMemoryLimit = 3

val forkedTestLimit = gradle.sharedServices.registerIfAbsent("forkedTestLimit", ForkedTestLimit::class.java) {
  maxParallelUsages.set(forkedTestsMemoryLimit)
}

extensions.findByType<TestingExtension>()?.apply {
  suites.withType<JvmTestSuite>().configureEach {
    // Use JUnit 5 to run tests
    useJUnitJupiter()
  }
}

// Use lazy providers to avoid evaluating the property until it is needed
val skipTestsProvider = rootProject.providers.gradleProperty("skipTests")
val skipForkedTestsProvider = rootProject.providers.gradleProperty("skipForkedTests")
val skipFlakyTestsProvider = rootProject.providers.gradleProperty("skipFlakyTests")
val runFlakyTestsProvider = rootProject.providers.gradleProperty("runFlakyTests")

// Go through the Test tasks and configure them
tasks.withType<Test>().configureEach {
  // Disable all tests if skipTests property was specified
  onlyIf("skipTests are undefined or false") { !skipTestsProvider.isPresent }

  // Enable force rerun of tests with -Prerun.tests.${project.name}
  outputs.upToDateWhen {
    !rootProject.providers.gradleProperty("rerun.tests.${project.name}").isPresent
  }

  // Split up tests that want to run forked in their own separate JVM for generated tasks
  if (name.startsWith("forkedTest") || name.endsWith("ForkedTest")) {
    setExcludes(emptyList())
    setIncludes(listOf("**/*ForkedTest*"))
    forkEvery = 1
    // Limit the number of concurrent forked tests
    usesService(forkedTestLimit)
    onlyIf("skipForkedTests are undefined or false") { !skipForkedTestsProvider.isPresent }
  } else {
    exclude("**/*ForkedTest*")
  }

  // Set test timeout for 20 minutes. Default job timeout is 1h (configured on CI level).
  timeout.set(Duration.of(20, ChronoUnit.MINUTES))
}

// Register a task "allTests" that depends on all non-latest and non-traceAgentTest Test tasks.
// This is used when we only want to run the 'main' test sets.
tasks.register("allTests") {
  dependsOn(tasks.withType<Test>().matching { testTask ->
    !testTask.name.contains("latest", ignoreCase = true) && testTask.name != "traceAgentTest"
  })
}

// Register a task "allLatestDepTests" that depends on all Test tasks whose names include 'latest'.
// This is used when we want to run tests against the latest dependency versions.
tasks.register("allLatestDepTests") {
  dependsOn(tasks.withType<Test>().matching { testTask ->
    !testTask.name.contains("latest", ignoreCase = true)
  })
}

// Make the 'check' task depend on all Test tasks in the project.
// This means that when running the 'check' task, all Test tasks will run as well.
tasks.named("check") {
  dependsOn(tasks.withType<Test>())
}

tasks.withType<Test>().configureEach {
  // Flaky tests management for JUnit 5
  (options as? JUnitPlatformOptions)?.apply {
    if (skipFlakyTestsProvider.isPresent) {
      excludeTags("flaky")
    } else if (runFlakyTestsProvider.isPresent) {
      includeTags("flaky")
    }
  }

  // Set system property flag that is checked from tests to determine if they should be skipped or run
  if (skipFlakyTestsProvider.isPresent) {
    jvmArgs("-Drun.flaky.tests=false")
  } else if (runFlakyTestsProvider.isPresent) {
    jvmArgs("-Drun.flaky.tests=true")
  }
}

tasks.withType<Test>().configureEach {
  // https://docs.gradle.com/develocity/flaky-test-detection/
  // https://docs.gradle.com/develocity/gradle-plugin/current/#test_retry
  develocity.testRetry {
    if (providers.environmentVariable("CI").isPresent()) {
      maxRetries = 3
    }
  }
}
