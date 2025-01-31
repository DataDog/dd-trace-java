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
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class KarateTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    Assumptions.assumeTrue(assumption)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName            | success | tests                          | assumption
    "test-succeed"          | true    | [TestSucceedKarate]            | true
    "test-succeed-parallel" | true    | [TestSucceedParallelKarate]    | true
    "test-with-setup"       | true    | [TestWithSetupKarate]          | isSetupTagSupported(FileUtils.KARATE_VERSION)
    "test-parameterized"    | true    | [TestParameterizedKarate]      | true
    "test-failed"           | false   | [TestFailedKarate]             | true
    "test-skipped-feature"  | true    | [TestSkippedFeatureKarate]     | true
    "test-built-in-retry"   | true    | [TestFailedBuiltInRetryKarate] | true
  }

  def "test ITR #testcaseName"() {
    Assumptions.assumeTrue(isSkippingSupported(FileUtils.KARATE_VERSION))

    givenSkippableTests(skippedTests)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                      | tests                     | skippedTests
    "test-itr-skipping"               | [TestSucceedKarate]       | [new TestIdentifier("[org/example/test_succeed] test succeed", "first scenario", null)]
    "test-itr-skipping-parameterized" | [TestParameterizedKarate] | [
      new TestIdentifier("[org/example/test_parameterized] test parameterized", "first scenario as an outline", '{"param":"\'a\'","value":"aa"}')
    ]
    "test-itr-unskippable"            | [TestUnskippableKarate]   | [new TestIdentifier("[org/example/test_unskippable] test unskippable", "first scenario", null)]
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName               | success | tests                           | retriedTests
    "test-failed"              | false   | [TestFailedKarate]              | []
    "test-retry-failed"        | false   | [TestFailedKarate]              | [new TestIdentifier("[org/example/test_failed] test failed", "second scenario", null)]
    "test-failed-then-succeed" | true    | [TestFailedThenSucceedKarate]   | [new TestIdentifier("[org/example/test_failed_then_succeed] test failed", "flaky scenario", null)]
    "test-retry-parameterized" | false   | [TestFailedParameterizedKarate] | [
      new TestIdentifier("[org/example/test_failed_parameterized] test parameterized", "first scenario as an outline", null)
    ]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | tests                              | knownTestsList
    "test-efd-known-test"               | [TestSucceedOneCaseKarate]         | [new TestIdentifier("[org/example/test_succeed_one_case] test succeed", "first scenario", null)]
    "test-efd-known-parameterized-test" | [TestParameterizedKarate]          | [
      new TestIdentifier("[org/example/test_parameterized] test parameterized", "first scenario as an outline", null)
    ]
    "test-efd-new-test"                 | [TestSucceedOneCaseKarate]         | []
    "test-efd-new-parameterized-test"   | [TestParameterizedKarate]          | []
    "test-efd-new-slow-test"            | [TestSucceedKarateSlow]            | [] // is executed only twice
    "test-efd-faulty-session-threshold" | [TestParameterizedMoreCasesKarate] | []
  }

  private void runTests(List<Class<?>> tests, boolean expectSuccess = true) {
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

  private static final class TestResultListener implements TestExecutionListener {
    private final Map<TestExecutionResult.Status, Collection<org.junit.platform.launcher.TestIdentifier>> testsByStatus = new ConcurrentHashMap<>()

    void executionFinished(org.junit.platform.launcher.TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
      testsByStatus.computeIfAbsent(testExecutionResult.status, k -> new CopyOnWriteArrayList<>()).add(testIdentifier)
    }
  }
}
