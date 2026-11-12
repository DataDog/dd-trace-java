import datadog.gradle.plugin.testing.GeneratedGroovyClosureSpec
import datadog.gradle.plugin.testing.configureTestAgentPostChecks
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.develocity
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.base.TestingExtension
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable

plugins {
  id("dd-trace-java.modifiable-config")
}

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
val rerunTestsProperty = "rerun.tests.${project.name}"
val rerunTestsProvider = rootProject.providers.gradleProperty(rerunTestsProperty)
val isMasterBranch = providers.environmentVariable("CI_COMMIT_BRANCH")
  .map { it == "master" }
  .orElse(false)

// Go through the Test tasks and configure them
tasks.withType<Test>().configureEach {
  configureTestAgentPostChecks()

  // Test suites get the JUnit Platform from `useJUnitJupiter()` above, but tasks registered with
  // `tasks.register(..., Test)` would default to JUnit 4 and discover no Spock 2 or JUnit 5 tests.
  useJUnitPlatform()

  // Disable all tests if skipTests property was specified
  onlyIf("skipTests are undefined or false") { !skipTestsProvider.isPresent }

  // Keep running tests on master even with the S3 build cache to keep
  // steady flaky-tests signals. Successful results still populate the cache.
  outputs.upToDateWhen { task ->
    val reason = when {
      isMasterBranch.get() -> "CI_COMMIT_BRANCH=master"
      rerunTestsProvider.isPresent -> "$rerunTestsProperty is set"
      else -> return@upToDateWhen true
    }
    task.logger.info("Disabling output reuse for {}: {}", task.path, reason)
    false
  }

  // Trick to avoid on CI: "Couldn't flush user prefs: java.util.prefs.BackingStoreException: Couldn't get file lock."
  // Use a task-specific user prefs directory
  systemProperty("java.util.prefs.userRoot", layout.buildDirectory.dir("tmp/userPrefs/$name").get().asFile.absolutePath)

  // Enable JUnit 5 auto-detection so ConfigInversionExtension (STRICT mode) is loaded automatically
  systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")

  // Split up tests that want to run forked in their own separate JVM for generated tasks
  if (name.startsWith("forkedTest") || name.endsWith("ForkedTest")) {
    setExcludes(emptyList())
    exclude(GeneratedGroovyClosureSpec())
    setIncludes(listOf("**/*ForkedTest*"))
    forkEvery = 1
    // Limit the number of concurrent forked tests
    usesService(forkedTestLimit)
    onlyIf("skipForkedTests are undefined or false") { !skipForkedTestsProvider.isPresent }
  } else {
    exclude("**/*ForkedTest*")
    // Gradle fails a test task that discovers no tests, which catches misconfigured tasks.
    // The exception is a suite whose concrete tests are all *ForkedTest classes: the exclude rule
    // above leaves its non-forked task with abstract base classes only, so allow it to be empty.
    // The compiled classes are read lazily, once the task inputs are snapshotted.
    val forkedTestClasses = objects.fileCollection()
      .from(Callable { testClassesDirs })
      .asFileTree
      .matching { include("**/*ForkedTest*.class") }
    failOnNoDiscoveredTests.set(forkedTestClasses.elements.map { it.isEmpty() })
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
    testTask.name.contains("latest", ignoreCase = true)
  })
}

// Make the 'check' task depend on all Test tasks in the project.
// This means that when running the 'check' task, all Test tasks will run as well.
tasks.named("check") {
  dependsOn(tasks.withType<Test>())
}

tasks.withType<Test>().configureEach {
  // Keep suites without test-utils out of flaky-only runs. Runtime extensions refine this tag.
  if (!skipFlakyTestsProvider.isPresent && runFlakyTestsProvider.isPresent) {
    useJUnitPlatform {
      includeTags("flaky")
    }
  }

  // Let the JUnit and Spock extensions evaluate @Flaky conditions before selecting tests.
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
      filter {
        excludeAnnotationClasses.add("*NonRetryable") // allow to mark classes non retryable
      }
    }
  }
}
