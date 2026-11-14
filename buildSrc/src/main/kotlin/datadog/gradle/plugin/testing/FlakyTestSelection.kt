package datadog.gradle.plugin.testing

import org.gradle.api.tasks.testing.Test

/** Selects flaky tests while allowing suites to become empty as flaky tests are fixed or removed. */
fun Test.configureFlakyTestSelection(skipFlakyTests: Boolean, runFlakyTests: Boolean) {
  // Keep suites without test-utils out of flaky-only runs. Runtime extensions refine this tag.
  if (!skipFlakyTests && runFlakyTests) {
    // Tag filtering can trigger either Gradle's discovery check or its include/exclude check.
    failOnNoDiscoveredTests.set(false)
    filter.isFailOnNoMatchingTests = false
    useJUnitPlatform {
      includeTags("flaky")
    }
  }

  // Let the JUnit and Spock extensions evaluate @Flaky conditions before selecting tests.
  if (skipFlakyTests) {
    jvmArgs("-Drun.flaky.tests=false")
  } else if (runFlakyTests) {
    jvmArgs("-Drun.flaky.tests=true")
  }
}
