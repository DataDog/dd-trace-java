import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.testing.base.TestingExtension
import org.gradle.api.plugins.jvm.JvmTestSuite
import java.time.Duration
import java.time.temporal.ChronoUnit

fun isTestingInstrumentation(project: Project): Boolean {
  return listOf(
    "cucumber",
    "cucumber-junit-4",
    "cucumber-junit-5",
    "junit-4.10",
    "junit-4.13",
    "junit-5.3",
    "junit-5.8",
    "karate",
    "munit-junit-4",
    "scalatest",
    "selenium",
    "spock-junit-5",
    "testng-6",
    "testng-7",
    "weaver"
  ).contains(project.name)
}

// Need concrete implementation of BuildService in Kotlin
abstract class ForkedTestLimit : BuildService<BuildServiceParameters.None>
// Forked tests will fail with OOM if the memory is set too high. Gitlab allows at least a limit of 3.
val forkedTestsMemoryLimit = 3

val forkedTestLimit = gradle.sharedServices.registerIfAbsent("forkedTestLimit", ForkedTestLimit::class.java) {
  maxParallelUsages.set(forkedTestsMemoryLimit)
}

extensions.findByType(TestingExtension::class.java)?.apply {
  suites.withType(JvmTestSuite::class.java).configureEach {
    // Use JUnit 5 to run tests
    useJUnitJupiter()
  }
}

// Use lazy providers to avoid evaluating the property until it is needed
val skipTestsProvider = rootProject.providers.gradleProperty("skipTests")
val skipForkedTestsProvider = rootProject.providers.gradleProperty("skipForkedTests")
val skipFlakyTestsProvider = rootProject.providers.gradleProperty("skipFlakyTests")
val runFlakyTestsProvider = rootProject.providers.gradleProperty("runFlakyTests")
val activePartitionProvider = providers.provider {
  project.extra.properties["activePartition"] as? Boolean ?: true
}

// Go through the Test tasks and configure them
tasks.withType<Test>().configureEach {
  enabled = activePartitionProvider.get()
  
  // Disable all tests if skipTests property was specified
  onlyIf { !skipTestsProvider.isPresent }

  // Enable force rerun of tests with -Prerun.tests.${project.name}
  outputs.upToDateWhen {
    !rootProject.providers.gradleProperty("rerun.tests.${project.name}").isPresent
  }

  // Avoid executing classes used to test testing frameworks instrumentation
  if (isTestingInstrumentation(project)) {
    exclude("**/TestAssumption*", "**/TestSuiteSetUpAssumption*")
    exclude("**/TestDisableTestTrace*")
    exclude("**/TestError*")
    exclude("**/TestFactory*")
    exclude("**/TestFailed*")
    exclude("**/TestFailedWithSuccessPercentage*")
    exclude("**/TestInheritance*", "**/BaseTestInheritance*")
    exclude("**/TestParameterized*")
    exclude("**/TestRepeated*")
    exclude("**/TestSkipped*")
    exclude("**/TestSkippedClass*")
    exclude("**/TestSucceed*")
    exclude("**/TestTemplate*")
    exclude("**/TestUnskippable*")
    exclude("**/TestWithSetup*")
  }

  // Split up tests that want to run forked in their own separate JVM for generated tasks
  if (name.startsWith("forkedTest") || name.endsWith("ForkedTest")) {
    setExcludes(emptyList())
    setIncludes(listOf("**/*ForkedTest*"))
    forkEvery = 1
    // Limit the number of concurrent forked tests
    usesService(forkedTestLimit)
    onlyIf { !skipForkedTestsProvider.isPresent }
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

// Make the 'check' task depends on all Test tasks in the project.
// This means that when running the 'check' task, all Test tasks will run as well.
tasks.named("check") {
  dependsOn(tasks.withType<Test>())
}

tasks.withType<Test>().configureEach {
  // Flaky tests management for JUnit 5
  if (testFramework is JUnitPlatformOptions) {
    val junitPlatform = testFramework as JUnitPlatformOptions
    if (skipFlakyTestsProvider.isPresent) {
      junitPlatform.excludeTags("flaky")
    } else if (runFlakyTestsProvider.isPresent) {
      junitPlatform.includeTags("flaky")
    }
  }

  // Flaky tests management for Spock
  if (skipFlakyTestsProvider.isPresent) {
    jvmArgs("-Drun.flaky.tests=false")
  } else if (runFlakyTestsProvider.isPresent) {
    jvmArgs("-Drun.flaky.tests=true")
  }
}

// tasks.withType(Test).configureEach {
//   // https://docs.gradle.com/develocity/flaky-test-detection/
//   // https://docs.gradle.com/develocity/gradle-plugin/current/#test_retry
//   develocity.testRetry {
//     if (providers.environmentVariable("CI").isPresent()) {
//       maxRetries = 3
//     }
//   }
// }
