import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import io.cucumber.core.api.TypeRegistry
import io.cucumber.core.options.Constants
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.EngineFilter
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource

@DisableTestTrace(reason = "avoid self-tracing")
class CucumberTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    setup:
    runFeatures(features, parallel)

    expect:
    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                                 | features                                                                                                                         | parallel | expectedTracesCount
    "test-succeed"                               | ["org/example/cucumber/calculator/basic_arithmetic.feature"]                                                                     | false    | 2
    "test-scenario-outline-${version()}"         | ["org/example/cucumber/calculator/basic_arithmetic_with_examples.feature"]                                                       | false    | 5
    "test-skipped"                               | ["org/example/cucumber/calculator/basic_arithmetic_skipped.feature"]                                                             | false    | 3
    "test-skipped-feature"                       | ["org/example/cucumber/calculator/basic_arithmetic_skipped_feature.feature"]                                                     | false    | 3
    "test-skipped-scenario-outline-${version()}" | ["org/example/cucumber/calculator/basic_arithmetic_with_examples_skipped.feature"]                                               | false    | 5
    "test-parallel"                              | [
      "org/example/cucumber/calculator/basic_arithmetic.feature",
      "org/example/cucumber/calculator/basic_arithmetic_skipped.feature"
    ] | true     | 4
  }

  def "test ITR #testcaseName"() {
    setup:
    givenSkippableTests(skippedTests)
    runFeatures(features, false)

    expect:
    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                 | features                                                                       | expectedTracesCount | skippedTests
    "test-itr-skipping"          | ["org/example/cucumber/calculator/basic_arithmetic.feature"]                   | 2                   | [new TestIdentifier("Basic Arithmetic", "Addition", null, null)]
    "test-itr-unskippable"       | ["org/example/cucumber/calculator/basic_arithmetic_unskippable.feature"]       | 2                   | [new TestIdentifier("Basic Arithmetic", "Addition", null, null)]
    "test-itr-unskippable-suite" | ["org/example/cucumber/calculator/basic_arithmetic_unskippable_suite.feature"] | 2                   | [new TestIdentifier("Basic Arithmetic", "Addition", null, null)]
  }

  def "test flaky retries #testcaseName"() {
    setup:
    givenFlakyTests(retriedTests)

    runFeatures(features, false)

    expect:
    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                                | features                                                                          | expectedTracesCount | retriedTests
    "test-failed"                               | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]               | 2                   | []
    "test-retry-failed"                         | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]               | 5                   | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition", null, null)
    ]
    "test-failed-then-succeed"                  | ["org/example/cucumber/calculator/basic_arithmetic_failed_then_succeed.feature"]  | 4                   | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_failed_then_succeed.feature:Basic Arithmetic", "Addition", null, null)
    ]
    "test-failed-scenario-outline-${version()}" | ["org/example/cucumber/calculator/basic_arithmetic_with_failed_examples.feature"] | 2                   | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_with_failed_examples.feature:Basic Arithmetic With Examples", "Many additions.Single digits.${parameterizedTestNameSuffix()}", null, null)
    ]
  }

  private String parameterizedTestNameSuffix() {
    // older releases report different example names
    version() == "5.4.0" ? "Example #1" : "Example #1.1"
  }

  private String version() {
    def version = TypeRegistry.package.getImplementationVersion()
    return version != null ? "latest" : "5.4.0" // older releases do not have package version populated
  }

  protected void runFeatures(List<String> classpathFeatures, boolean parallel) {
    TestEventsHandlerHolder.start()

    DiscoverySelector[] selectors = new DiscoverySelector[classpathFeatures.size()]
    for (i in 0..<classpathFeatures.size()) {
      selectors[i] = selectClasspathResource(classpathFeatures[i])
    }

    LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
      .filters(EngineFilter.includeEngines("cucumber"))
      .configurationParameter(Constants.GLUE_PROPERTY_NAME, "org.example.cucumber.calculator")
      .configurationParameter(Constants.FILTER_TAGS_PROPERTY_NAME, "not @Disabled")
      .configurationParameter(io.cucumber.junit.platform.engine.Constants.PARALLEL_EXECUTION_ENABLED_PROPERTY_NAME, "$parallel")
      .selectors(selectors)
      .build()

    Launcher launcher = LauncherFactory.create()
    launcher.execute(request)

    TestEventsHandlerHolder.stop()
  }

  @Override
  String instrumentedLibraryName() {
    return "cucumber"
  }

  @Override
  String instrumentedLibraryVersion() {
    def version = TypeRegistry.package.getImplementationVersion()
    return version != null ? version : "5.4.0" // older releases do not have package version populated
  }
}
