import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit4.CucumberTracingListener
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import io.cucumber.core.options.Constants
import org.example.cucumber.TestSucceedCucumber
import org.junit.runner.JUnitCore

import java.util.stream.Collectors

@DisableTestTrace(reason = "avoid self-tracing")
class CucumberTest extends CiVisibilityInstrumentationTest {

  def runner = new JUnitCore()

  def "test #testcaseName"() {
    runFeatures(features, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                          | success | features
    "test-succeed"                        | true    | ["org/example/cucumber/calculator/basic_arithmetic.feature"]
    "test-scenario-outline-${version()}"  | true    | ["org/example/cucumber/calculator/basic_arithmetic_with_examples.feature"]
    "test-failure"                        | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]
    "test-multiple-features-${version()}" | false   | [
      "org/example/cucumber/calculator/basic_arithmetic.feature",
      "org/example/cucumber/calculator/basic_arithmetic_failed.feature"
    ]
    "test-name-with-brackets"             | true    | ["org/example/cucumber/calculator/name_with_brackets.feature"]
    "test-empty-name-${version()}"        | true    | ["org/example/cucumber/calculator/empty_scenario_name.feature"]
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)

    runFeatures(features)

    assertSpansData(testcaseName)

    where:
    testcaseName                 | features                                                                       | skippedTests
    "test-itr-skipping"          | ["org/example/cucumber/calculator/basic_arithmetic.feature"]                   | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition", null)
    ]
    "test-itr-unskippable"       | ["org/example/cucumber/calculator/basic_arithmetic_unskippable.feature"]       | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_unskippable.feature:Basic Arithmetic", "Addition", null)
    ]
    "test-itr-unskippable-suite" | ["org/example/cucumber/calculator/basic_arithmetic_unskippable_suite.feature"] | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_unskippable_suite.feature:Basic Arithmetic", "Addition", null)
    ]
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)

    runFeatures(features, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                               | success | features                                                                          | retriedTests
    "test-failure"                             | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]               | []
    "test-retry-failure"                       | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]               | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition", null)
    ]
    "test-retry-scenario-outline-${version()}" | false   | ["org/example/cucumber/calculator/basic_arithmetic_with_examples_failed.feature"] | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_with_examples_failed.feature:Basic Arithmetic With Examples", "Many additions", null)
    ]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runFeatures(features)

    assertSpansData(testcaseName)

    where:
    testcaseName                                 | features                                                                   | knownTestsList
    "test-efd-known-test"                        | ["org/example/cucumber/calculator/basic_arithmetic.feature"]               | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition", null)
    ]
    "test-efd-new-test"                          | ["org/example/cucumber/calculator/basic_arithmetic.feature"]               | []
    "test-efd-new-scenario-outline-${version()}" | ["org/example/cucumber/calculator/basic_arithmetic_with_examples.feature"] | []
    "test-efd-new-slow-test"                     | ["org/example/cucumber/calculator/basic_arithmetic_slow.feature"]          | []
  }

  private String version() {
    return CucumberTracingListener.FRAMEWORK_VERSION < "7" ? CucumberTracingListener.FRAMEWORK_VERSION : "latest"
  }

  private void runFeatures(List<String> classpathFeatures, boolean expectSuccess = true) {
    System.setProperty(Constants.GLUE_PROPERTY_NAME, "org.example.cucumber.calculator")
    System.setProperty(Constants.FILTER_TAGS_PROPERTY_NAME, "not @Disabled")
    System.setProperty(Constants.FEATURES_PROPERTY_NAME, classpathFeatures.stream()
    .map(f -> "classpath:" + f).
    collect(Collectors.joining(",")))

    TestEventsHandlerHolder.start()
    try {
      def result = runner.run(TestSucceedCucumber)
      if (expectSuccess) {
        if (result.getFailureCount() > 0) {
          throw new AssertionError("Expected successful execution, got following failures: " + result.getFailures())
        }
      } else {
        if (result.getFailureCount() == 0) {
          throw new AssertionError("Expected a failed execution, got no failures")
        }
      }
    } finally {
      TestEventsHandlerHolder.stop()
    }
  }

  @Override
  String instrumentedLibraryName() {
    CucumberTracingListener.FRAMEWORK_NAME
  }

  @Override
  String instrumentedLibraryVersion() {
    CucumberTracingListener.FRAMEWORK_VERSION
  }
}
