import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.SkippableTest
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
    setup:
    givenSkippableTests(skippedTests)

    runFeatures(features)

    expect:
    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                          | features                                                                                                                        | expectedTracesCount | skippedTests
    "test-succeed"                        | ["org/example/cucumber/calculator/basic_arithmetic.feature"]                                                                    | 2                   | []
    "test-scenario-outline-${version()}"  | ["org/example/cucumber/calculator/basic_arithmetic_with_examples.feature"]                                                      | 5                   | []
    "test-failure"                        | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]                                                             | 2                   | []
    "test-itr-skipping"                   | ["org/example/cucumber/calculator/basic_arithmetic.feature"]                                                                    | 2                   | [new SkippableTest("Basic Arithmetic", "Addition", null, null)]
    "test-itr-unskippable"                | ["org/example/cucumber/calculator/basic_arithmetic_unskippable.feature"]                                                        | 2                   | [new SkippableTest("Basic Arithmetic", "Addition", null, null)]
    "test-itr-unskippable-suite"          | ["org/example/cucumber/calculator/basic_arithmetic_unskippable_suite.feature"]                                                  | 2                   | [new SkippableTest("Basic Arithmetic", "Addition", null, null)]
    "test-multiple-features-${version()}" | [
      "org/example/cucumber/calculator/basic_arithmetic.feature",
      "org/example/cucumber/calculator/basic_arithmetic_failed.feature"
    ] | 3                   | []
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
