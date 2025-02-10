import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.FileDiff
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import org.example.TestFailedParameterizedSpock
import org.example.TestFailedSpock
import org.example.TestFailedThenSucceedParameterizedSpock
import org.example.TestFailedThenSucceedSpock
import org.example.TestParameterizedSetupSpecSpock
import org.example.TestParameterizedSpock
import org.example.TestSucceedAndFailedSpock
import org.example.TestSucceedSetupSpecSpock
import org.example.TestSucceedSpock
import org.example.TestSucceedSpockSlow
import org.example.TestSucceedSpockUnskippable
import org.example.TestSucceedSpockUnskippableSuite
import org.example.TestSucceedSpockVerySlow
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.spockframework.runtime.SpockEngine
import org.spockframework.util.SpockReleaseInfo

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class SpockTest extends CiVisibilityInstrumentationTest {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
  }

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                 | tests
    "test-succeed"               | [TestSucceedSpock]
    "test-succeed-parameterized" | [TestParameterizedSpock]
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)
    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                                     | tests                              | skippedTests
    "test-itr-skipping"                              | [TestSucceedSpock]                 | [new TestIdentifier("org.example.TestSucceedSpock", "test success", null)]
    "test-itr-skipping-parameterized"                | [TestParameterizedSpock]           | [
      new TestIdentifier("org.example.TestParameterizedSpock", "test add 1 and 2", '{"metadata":{"test_name":"test add 1 and 2"}}')
    ]
    "test-itr-unskippable"                           | [TestSucceedSpockUnskippable]      | [new TestIdentifier("org.example.TestSucceedSpockUnskippable", "test success", null)]
    "test-itr-unskippable-suite"                     | [TestSucceedSpockUnskippableSuite] | [new TestIdentifier("org.example.TestSucceedSpockUnskippableSuite", "test success", null)]
    "test-itr-skipping-spec-setup"                   | [TestSucceedSetupSpecSpock]        | [
      new TestIdentifier("org.example.TestSucceedSetupSpecSpock", "test success", null),
      new TestIdentifier("org.example.TestSucceedSetupSpecSpock", "test another success", null)
    ]
    "test-itr-not-skipping-spec-setup"               | [TestSucceedSetupSpecSpock]        | [new TestIdentifier("org.example.TestSucceedSetupSpecSpock", "test success", null)]
    "test-itr-not-skipping-parameterized-spec-setup" | [TestParameterizedSetupSpecSpock]  | [
      new TestIdentifier("org.example.TestParameterizedSetupSpecSpock", "test add 1 and 2", '{"metadata":{"test_name":"test add 1 and 2"}}')
    ]
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                             | success | tests                                     | retriedTests
    "test-failed"                            | false   | [TestFailedSpock]                         | []
    "test-retry-failed"                      | false   | [TestFailedSpock]                         | [new TestIdentifier("org.example.TestFailedSpock", "test failed", null)]
    "test-failed-then-succeed"               | true    | [TestFailedThenSucceedSpock]              | [new TestIdentifier("org.example.TestFailedThenSucceedSpock", "test failed then succeed", null)]
    "test-retry-parameterized"               | false   | [TestFailedParameterizedSpock]            | [new TestIdentifier("org.example.TestFailedParameterizedSpock", "test add 4 and 4", null)]
    "test-parameterized-failed-then-succeed" | true    | [TestFailedThenSucceedParameterizedSpock] | [new TestIdentifier("org.example.TestFailedThenSucceedParameterizedSpock", "test add 1 and 2", null)]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | success | tests                       | knownTestsList
    "test-efd-known-test"               | true    | [TestSucceedSpock]          | [new TestIdentifier("org.example.TestSucceedSpock", "test success", null)]
    "test-efd-known-parameterized-test" | true    | [TestParameterizedSpock]    | [
      new TestIdentifier("org.example.TestParameterizedSpock", "test add 1 and 2", null),
      new TestIdentifier("org.example.TestParameterizedSpock", "test add 4 and 4", null)
    ]
    "test-efd-new-test"                 | true    | [TestSucceedSpock]          | []
    "test-efd-new-parameterized-test"   | true    | [TestParameterizedSpock]    | []
    "test-efd-known-tests-and-new-test" | true    | [TestParameterizedSpock]    | [new TestIdentifier("org.example.TestParameterizedSpock", "test add 1 and 2", null)]
    "test-efd-new-slow-test"            | true    | [TestSucceedSpockSlow]      | [] // is executed only twice
    "test-efd-new-very-slow-test"       | true    | [TestSucceedSpockVerySlow]  | [] // is executed only once
    "test-efd-faulty-session-threshold" | false   | [TestSucceedAndFailedSpock] | []
  }

  def "test impacted tests detection #testcaseName"() {
    givenImpactedTestsDetectionEnabled(true)
    givenDiff(prDiff)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName            | tests              | prDiff
    "test-succeed"          | [TestSucceedSpock] | LineDiff.EMPTY
    "test-succeed"          | [TestSucceedSpock] | new FileDiff(new HashSet())
    "test-succeed-impacted" | [TestSucceedSpock] | new FileDiff(new HashSet([DUMMY_SOURCE_PATH]))
    "test-succeed"          | [TestSucceedSpock] | new LineDiff([(DUMMY_SOURCE_PATH): lines()])
    "test-succeed-impacted" | [TestSucceedSpock] | new LineDiff([(DUMMY_SOURCE_PATH): lines(DUMMY_TEST_METHOD_START)])
  }

  def "test quarantined #testcaseName"() {
    givenTestManagementEnabled(true)
    givenQuarantinedTests(quarantined)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                            | tests                          | quarantined
    "test-quarantined-failed"               | [TestFailedSpock]              | [new TestIdentifier("org.example.TestFailedSpock", "test failed", null)]
    "test-quarantined-failed-parameterized" | [TestFailedParameterizedSpock] | [new TestIdentifier("org.example.TestFailedParameterizedSpock", "test add 4 and 4", null)]
  }

  def "test quarantined auto-retries #testcaseName"() {
    givenTestManagementEnabled(true)
    givenQuarantinedTests(quarantined)

    givenFlakyRetryEnabled(true)
    givenFlakyTests(retried)

    // every test retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                  | tests             | quarantined                                                              | retried
    "test-quarantined-failed-atr" | [TestFailedSpock] | [new TestIdentifier("org.example.TestFailedSpock", "test failed", null)] | [new TestIdentifier("org.example.TestFailedSpock", "test failed", null)]
  }

  def "test quarantined early flakiness detection #testcaseName"() {
    givenTestManagementEnabled(true)
    givenQuarantinedTests(quarantined)

    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(known)

    // every retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                    | tests             | quarantined                                                              | known
    "test-quarantined-failed-known" | [TestFailedSpock] | [new TestIdentifier("org.example.TestFailedSpock", "test failed", null)] | [new TestIdentifier("org.example.TestFailedSpock", "test failed", null)]
    "test-quarantined-failed-efd"   | [TestFailedSpock] | [new TestIdentifier("org.example.TestFailedSpock", "test failed", null)] | []
  }

  private static void runTests(List<Class<?>> classes, boolean expectSuccess = true) {
    TestEventsHandlerHolder.startForcefully()

    DiscoverySelector[] selectors = new DiscoverySelector[classes.size()]
    for (i in 0..<classes.size()) {
      selectors[i] = selectClass(classes[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
    .selectors(selectors)
    .build()

    def launcherConfig = LauncherConfig
    .builder()
    .enableTestEngineAutoRegistration(false)
    .addTestEngines(new SpockEngine())
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
    return "spock"
  }

  @Override
  String instrumentedLibraryVersion() {
    return SpockReleaseInfo.version
  }

  private static final class TestResultListener implements TestExecutionListener {
    private final Map<TestExecutionResult.Status, Collection<org.junit.platform.launcher.TestIdentifier>> testsByStatus = new ConcurrentHashMap<>()

    void executionFinished(org.junit.platform.launcher.TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
      testsByStatus.computeIfAbsent(testExecutionResult.status, k -> new CopyOnWriteArrayList<>()).add(testIdentifier)
    }
  }
}
