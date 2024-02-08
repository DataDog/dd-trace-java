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
    runFeatures(features)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                          | features                                                                   | expectedTracesCount
    "test-succeed"                        | ["org/example/cucumber/calculator/basic_arithmetic.feature"]               | 2
    "test-scenario-outline-${version()}"  | ["org/example/cucumber/calculator/basic_arithmetic_with_examples.feature"] | 5
    "test-failure"                        | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]        | 2
    "test-multiple-features-${version()}" | [
      "org/example/cucumber/calculator/basic_arithmetic.feature",
      "org/example/cucumber/calculator/basic_arithmetic_failed.feature"
    ]                                                                                                                  | 3
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)

    runFeatures(features)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                 | features                                                                       | expectedTracesCount | skippedTests
    "test-itr-skipping"          | ["org/example/cucumber/calculator/basic_arithmetic.feature"]                   | 2                   | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition", null, null)
    ]
    "test-itr-unskippable"       | ["org/example/cucumber/calculator/basic_arithmetic_unskippable.feature"]       | 2                   | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_unskippable.feature:Basic Arithmetic", "Addition", null, null)
    ]
    "test-itr-unskippable-suite" | ["org/example/cucumber/calculator/basic_arithmetic_unskippable_suite.feature"] | 2                   | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_unskippable_suite.feature:Basic Arithmetic", "Addition", null, null)
    ]
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyTests(retriedTests)

    runFeatures(features)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                               | features                                                                          | expectedTracesCount | retriedTests
    "test-failure"                             | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]               | 2                   | []
    "test-retry-failure"                       | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]               | 6                   | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition", null, null)
    ]
    "test-retry-scenario-outline-${version()}" | ["org/example/cucumber/calculator/basic_arithmetic_with_examples_failed.feature"] | 5                   | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_with_examples_failed.feature:Basic Arithmetic With Examples", "Many additions", null, null)
    ]
  }

  private String version() {
    return CucumberTracingListener.FRAMEWORK_VERSION < "7" ? CucumberTracingListener.FRAMEWORK_VERSION : "latest"
  }

  private void runFeatures(List<String> classpathFeatures) {
    System.setProperty(Constants.GLUE_PROPERTY_NAME, "org.example.cucumber.calculator")
    System.setProperty(Constants.FILTER_TAGS_PROPERTY_NAME, "not @Disabled")
    System.setProperty(Constants.FEATURES_PROPERTY_NAME, classpathFeatures.stream()
    .map(f -> "classpath:" + f).
    collect(Collectors.joining(",")))

    TestEventsHandlerHolder.start()
    runner.run(TestSucceedCucumber)
    TestEventsHandlerHolder.stop()
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
