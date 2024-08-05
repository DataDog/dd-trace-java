import com.intuit.karate.FileUtils
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.karate.TestEventsHandlerHolder
import org.example.TestFailedBuiltInRetryKarate
import org.example.TestFailedKarate
import org.example.TestFailedParameterizedKarate
import org.example.TestFailedThenSucceedKarate
import org.example.TestParameterizedKarate
import org.example.TestParameterizedMoreCasesKarate
import org.example.TestSkippedFeatureKarate
import org.example.TestSucceedKarate
import org.example.TestSucceedKarateSlow
import org.example.TestSucceedOneCaseKarate
import org.example.TestSucceedParallelKarate
import org.example.TestUnskippableKarate
import org.example.TestWithSetupKarate
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class KarateTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    Assumptions.assumeTrue(assumption)

    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName            | tests                          | expectedTracesCount | assumption
    "test-succeed"          | [TestSucceedKarate]            | 3                   | true
    "test-succeed-parallel" | [TestSucceedParallelKarate]    | 3                   | true
    "test-with-setup"       | [TestWithSetupKarate]          | 3                   | isSetupTagSupported(FileUtils.KARATE_VERSION)
    "test-parameterized"    | [TestParameterizedKarate]      | 3                   | true
    "test-failed"           | [TestFailedKarate]             | 3                   | true
    "test-skipped-feature"  | [TestSkippedFeatureKarate]     | 1                   | true
    "test-built-in-retry"   | [TestFailedBuiltInRetryKarate] | 4                   | true
  }

  def "test ITR #testcaseName"() {
    Assumptions.assumeTrue(isSkippingSupported(FileUtils.KARATE_VERSION))

    givenSkippableTests(skippedTests)

    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                      | tests                     | expectedTracesCount | skippedTests
    "test-itr-skipping"               | [TestSucceedKarate]       | 3                   | [new TestIdentifier("[org/example/test_succeed] test succeed", "first scenario", null, null)]
    "test-itr-skipping-parameterized" | [TestParameterizedKarate] | 3                   | [
      new TestIdentifier("[org/example/test_parameterized] test parameterized", "first scenario as an outline", '{"param":"\\\'a\\\'","value":"aa"}', null)
    ]
    "test-itr-unskippable"            | [TestUnskippableKarate]   | 3                   | [new TestIdentifier("[org/example/test_unskippable] test unskippable", "first scenario", null, null)]
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyTests(retriedTests)

    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName               | tests                           | expectedTracesCount | retriedTests
    "test-failed"              | [TestFailedKarate]              | 3                   | []
    "test-retry-failed"        | [TestFailedKarate]              | 3                   | [new TestIdentifier("[org/example/test_failed] test failed", "second scenario", null, null)]
    "test-failed-then-succeed" | [TestFailedThenSucceedKarate]   | 3                   | [
      new TestIdentifier("[org/example/test_failed_then_succeed] test failed", "flaky scenario", null, null)
    ]
    "test-retry-parameterized" | [TestFailedParameterizedKarate] | 3                   | [
      new TestIdentifier("[org/example/test_failed_parameterized] test parameterized", "first scenario as an outline", null, null)
    ]
  }

  def "test early flakiness detection #testcaseName"() {
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                        | tests                              | expectedTracesCount | knownTestsList
    "test-efd-known-test"               | [TestSucceedOneCaseKarate]         | 2                   | [
      new TestIdentifier("[org/example/test_succeed_one_case] test succeed", "first scenario", null, null)
    ]
    "test-efd-known-parameterized-test" | [TestParameterizedKarate]          | 3                   | [
      new TestIdentifier("[org/example/test_parameterized] test parameterized", "first scenario as an outline", null, null)
    ]
    "test-efd-new-test"                 | [TestSucceedOneCaseKarate]         | 4                   | []
    "test-efd-new-parameterized-test"   | [TestParameterizedKarate]          | 7                   | []
    "test-efd-new-slow-test"            | [TestSucceedKarateSlow]            | 3                   | [] // is executed only twice
    "test-efd-faulty-session-threshold" | [TestParameterizedMoreCasesKarate] | 8                   | []
  }

  private void runTests(List<Class<?>> tests) {
    TestEventsHandlerHolder.start()

    DiscoverySelector[] selectors = new DiscoverySelector[tests.size()]
    for (i in 0..<tests.size()) {
      selectors[i] = selectClass(tests[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectors)
      .build()

    def launcherConfig = LauncherConfig
      .builder()
      .enableTestEngineAutoRegistration(false)
      .addTestEngines(new JupiterTestEngine())
      .build()

    def launcher = LauncherFactory.create(launcherConfig)
    launcher.execute(launcherReq)

    TestEventsHandlerHolder.stop()
  }

  @Override
  String instrumentedLibraryName() {
    return "karate"
  }

  @Override
  String instrumentedLibraryVersion() {
    return FileUtils.KARATE_VERSION
  }

  boolean isSkippingSupported(String frameworkVersion) {
    // earlier Karate version contain a bug that does not allow skipping scenarios
    frameworkVersion >= "1.2.0"
  }

  boolean isSetupTagSupported(String frameworkVersion) {
    frameworkVersion >= "1.3.0"
  }
}
