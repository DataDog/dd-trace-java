import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import datadog.trace.util.ComparableVersion
import io.cucumber.core.api.TypeRegistry
import io.cucumber.core.options.Constants
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.EngineFilter
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource

@DisableTestTrace(reason = "avoid self-tracing")
class CucumberTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    runFeatures(features, parallel)

    assertSpansData(testcaseName)

    where:
    testcaseName                                 | features                                                                           | parallel
    "test-succeed"                               | ["org/example/cucumber/calculator/basic_arithmetic.feature"]                       | false
    "test-scenario-outline-${fixtureVersion()}"         | ["org/example/cucumber/calculator/basic_arithmetic_with_examples.feature"]         | false
    "test-skipped"                               | ["org/example/cucumber/calculator/basic_arithmetic_skipped.feature"]               | false
    "test-skipped-feature"                       | ["org/example/cucumber/calculator/basic_arithmetic_skipped_feature.feature"]       | false
    "test-skipped-scenario-outline-${fixtureVersion()}" | ["org/example/cucumber/calculator/basic_arithmetic_with_examples_skipped.feature"] | false
    "test-parallel"                              | [
      "org/example/cucumber/calculator/basic_arithmetic.feature",
      "org/example/cucumber/calculator/basic_arithmetic_skipped.feature"
    ]                                                                                                                                 | true
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)

    runFeatures(features, false)

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

    runFeatures(features, false, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                      | success | features                                                                          | retriedTests
    "test-no-retry-failed"                            | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]               | []
    "test-retry-failed"                               | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]               | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
    "test-failed-then-succeed"                        | true    | ["org/example/cucumber/calculator/basic_arithmetic_failed_then_succeed.feature"]  | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed_then_succeed.feature:Basic Arithmetic", "Addition")
    ]
    "test-retry-failed-scenario-outline-${fixtureVersion()}" | false   | ["org/example/cucumber/calculator/basic_arithmetic_with_failed_examples.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_with_failed_examples.feature:Basic Arithmetic With Examples", "Many additions.Single digits.${parameterizedTestNameSuffix()}")
    ]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runFeatures(features, false)

    assertSpansData(testcaseName)

    where:
    testcaseName                                 | features                                                                   | knownTestsList
    "test-efd-known-test"                        | ["org/example/cucumber/calculator/basic_arithmetic.feature"]               | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]
    "test-efd-new-test"                          | ["org/example/cucumber/calculator/basic_arithmetic.feature"]               | []
    "test-efd-new-scenario-outline-${fixtureVersion()}" | ["org/example/cucumber/calculator/basic_arithmetic_with_examples.feature"] | []
    "test-efd-new-slow-test"                     | ["org/example/cucumber/calculator/basic_arithmetic_slow.feature"]          | []
    "test-efd-skip-new-test"                     | ["org/example/cucumber/calculator/basic_arithmetic_skip_efd.feature"]      | []
  }

  def "test quarantined #testcaseName"() {
    givenQuarantinedTests(quarantined)

    runFeatures(features, false, true)

    assertSpansData(testcaseName)

    where:
    testcaseName              | features                                                            | quarantined
    "test-quarantined-failed" | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
  }

  def "test quarantined auto-retries #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenFlakyRetryEnabled(true)
    givenFlakyTests(retried)

    // every test retry fails, but the build status is successful
    runFeatures(features, false, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                  | features                                                            | quarantined       | retried
    "test-quarantined-failed-atr" | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                       | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
  }

  def "test quarantined early flakiness detection #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(known)

    // every retry fails, but the build status is successful
    runFeatures(features, false, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                    | features                                                            | quarantined     | known
    "test-quarantined-failed-known" | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                       | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
    "test-quarantined-failed-efd"   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                       | []
  }

  def "test disabled #testcaseName"() {
    givenDisabledTests(disabled)

    runFeatures(features, false, true)

    assertSpansData(testcaseName)

    where:
    testcaseName           | features                                                            | disabled
    "test-disabled-failed" | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
  }

  def "test attempt to fix #testcaseName"() {
    givenQuarantinedTests(quarantined)
    givenDisabledTests(disabled)
    givenAttemptToFixTests(attemptToFix)

    runFeatures(features, false, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                | success | features                                                            | attemptToFix | quarantined | disabled
    "test-attempt-to-fix-failed"                | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | []          | []
    "test-attempt-to-fix-succeeded"             | true    | ["org/example/cucumber/calculator/basic_arithmetic.feature"]        | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | []          | []
    "test-attempt-to-fix-quarantined-failed"    | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                                        | []
    "test-attempt-to-fix-quarantined-succeeded" | true    | ["org/example/cucumber/calculator/basic_arithmetic.feature"]        | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                                        | []
    "test-attempt-to-fix-disabled-failed"       | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | []          | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
    "test-attempt-to-fix-disabled-succeeded"    | true    | ["org/example/cucumber/calculator/basic_arithmetic.feature"]        | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | []          | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]
  }

  def "test capabilities tagging #testcaseName"() {
    setup:
    runFeatures(["org/example/cucumber/calculator/basic_arithmetic.feature"], false, true)

    expect:
    assertCapabilities(JUnitPlatformUtils.CUCUMBER_CAPABILITIES, 4)
  }

  private String parameterizedTestNameSuffix() {
    // Cucumber 7.11.0 changed scenario-outline example naming from the flat "Example #<row>"
    // to "Example #<examplesBlock>.<row>".
    usesFlatExampleNaming() ? "Example #1" : "Example #1.1"
  }

  private String fixtureVersion() {
    // Scenario-outline fixtures only differ by the example naming scheme, so every release that
    // still uses the flat naming shares the "legacy" fixture bucket; the rest use "latest".
    usesFlatExampleNaming() ? "legacy" : "latest"
  }

  private boolean usesFlatExampleNaming() {
    def version = TypeRegistry.package.getImplementationVersion()
    // 5.4.0 does not populate the package version; cucumber 7.11.0 switched from the flat
    // "Example #<row>" naming to "Example #<examplesBlock>.<row>".
    return version == null || new ComparableVersion(version) < new ComparableVersion("7.11.0")
  }

  protected void runFeatures(List<String> classpathFeatures, boolean parallel, boolean expectSuccess = true) {
    DiscoverySelector[] selectors = new DiscoverySelector[classpathFeatures.size()]
    for (i in 0..<classpathFeatures.size()) {
      selectors[i] = selectClasspathResource(classpathFeatures[i])
    }

    LauncherDiscoveryRequest launcherReq = LauncherDiscoveryRequestBuilder.request()
    .filters(EngineFilter.includeEngines("cucumber"))
    .configurationParameter(Constants.GLUE_PROPERTY_NAME, "org.example.cucumber.calculator")
    .configurationParameter(Constants.FILTER_TAGS_PROPERTY_NAME, "not @Disabled")
    .configurationParameter(io.cucumber.junit.platform.engine.Constants.PARALLEL_EXECUTION_ENABLED_PROPERTY_NAME, "$parallel")
    .selectors(selectors)
    .build()

    Launcher launcher = LauncherFactory.create()
    def listener = new TestResultListener()
    launcher.registerTestExecutionListeners(listener)
    try {
      launcher.execute(launcherReq)

      def failedTests = listener.testsByStatus[TestExecutionResult.Status.FAILED]
      if (expectSuccess) {
        if (failedTests != null && !failedTests.isEmpty()) {
          throw new AssertionError("Expected successful execution, the following tests were reported as failed: " + failedTests)
        }
      } else {
        if (failedTests == null || failedTests.isEmpty()) {
          throw new AssertionError("Expected a failed execution, got no failed tests")
        }
      }
    } finally {
      TestEventsHandlerHolder.stop()
    }
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

  private static final class TestResultListener implements TestExecutionListener {
    private final Map<TestExecutionResult.Status, Collection<org.junit.platform.launcher.TestIdentifier>> testsByStatus = new ConcurrentHashMap<>()

    void executionFinished(org.junit.platform.launcher.TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
      testsByStatus.computeIfAbsent(testExecutionResult.status, k -> new CopyOnWriteArrayList<>()).add(testIdentifier)
    }
  }
}
