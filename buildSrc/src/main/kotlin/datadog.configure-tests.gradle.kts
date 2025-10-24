import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.time.Duration
import java.time.temporal.ChronoUnit

fun isTestingInstrumentation(project: Project): Boolean {
  return listOf(
    "junit-4.10",
    "cucumber",
    "cucumber-junit-4",
    "junit-4.13",
    "munit-junit-4",
    "junit-5.3",
    "junit-5.8",
    "cucumber-junit-5",
    "spock-junit-5",
    "testng-6",
    "testng-7",
    "karate",
    "scalatest",
    "selenium",
    "weaver"
  ).contains(project.name)
}

// Need concrete implementation of BuildService in Kotlin
abstract class ForkedTestLimit : BuildService<BuildServiceParameters.None>

val forkedTestLimit = gradle.sharedServices.registerIfAbsent("forkedTestLimit", ForkedTestLimit::class.java) {
  maxParallelUsages.set(3)
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
tasks.withType(Test::class.java).configureEach {
  enabled = activePartitionProvider.get()
  
  // Disable all tests if skipTests property was specified
  onlyIf { !skipTestsProvider.isPresent }

  // Enable force rerun of tests with -Prerun.tests.${project.name}
  outputs.upToDateWhen {
    !rootProject.providers.gradleProperty("rerun.tests.${project.name}").isPresent
  }

  // Avoid executing classes used to test testing frameworks instrumentation
  if (isTestingInstrumentation(project)) {
    exclude("**/TestSucceed*")
    exclude("**/TestFailed*")
    exclude("**/TestFailedWithSuccessPercentage*")
    exclude("**/TestError*")
    exclude("**/TestSkipped*")
    exclude("**/TestSkippedClass*")
    exclude("**/TestInheritance*", "**/BaseTestInheritance*")
    exclude("**/TestFactory*")
    exclude("**/TestParameterized*")
    exclude("**/TestRepeated*")
    exclude("**/TestTemplate*")
    exclude("**/TestDisableTestTrace*")
    exclude("**/TestAssumption*", "**/TestSuiteSetUpAssumption*")
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

tasks.register("allTests") {
  dependsOn(providers.provider {
    tasks.withType<Test>().filter { testTask ->
      !testTask.name.contains("latest", ignoreCase = true) && testTask.name != "traceAgentTest"
    }
  })
}

tasks.register("allLatestDepTests") {
  dependsOn(providers.provider {
    tasks.withType<Test>().filter { testTask ->
      testTask.name.contains("latest", ignoreCase = true)
    }
  })
}

tasks.named("check") {
  dependsOn(tasks.withType<Test>())
}

tasks.withType(Test::class.java).configureEach {
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
