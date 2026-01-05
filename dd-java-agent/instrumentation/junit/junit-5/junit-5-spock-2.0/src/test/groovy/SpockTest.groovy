import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.example.TestFailedParameterizedSpock
import org.example.TestFailedSpock
import org.example.TestFailedThenSucceedParameterizedSpock
import org.example.TestFailedThenSucceedSpock
import org.example.TestParameterizedSetupSpecSpock
import org.example.TestParameterizedSpock
import org.example.TestSucceedAndFailedSpock
import org.example.TestSucceedSetupSpecSpock
import org.example.TestSucceedSpock
import org.example.TestSucceedSpockSkipEfd
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

@DisableTestTrace(reason = "avoid self-tracing")
class SpockTest extends CiVisibilityInstrumentationTest {
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
    "test-retry-failed"                      | false   | [TestFailedSpock]                         | [new TestFQN("org.example.TestFailedSpock", "test failed")]
    "test-failed-then-succeed"               | true    | [TestFailedThenSucceedSpock]              | [new TestFQN("org.example.TestFailedThenSucceedSpock", "test failed then succeed")]
    "test-retry-parameterized"               | false   | [TestFailedParameterizedSpock]            | [new TestFQN("org.example.TestFailedParameterizedSpock", "test add 4 and 4")]
    "test-parameterized-failed-then-succeed" | true    | [TestFailedThenSucceedParameterizedSpock] | [new TestFQN("org.example.TestFailedThenSucceedParameterizedSpock", "test add 1 and 2")]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | success | tests                       | knownTestsList
    "test-efd-known-test"               | true    | [TestSucceedSpock]          | [new TestFQN("org.example.TestSucceedSpock", "test success")]
    "test-efd-known-parameterized-test" | true    | [TestParameterizedSpock]    | [
      new TestFQN("org.example.TestParameterizedSpock", "test add 1 and 2"),
      new TestFQN("org.example.TestParameterizedSpock", "test add 4 and 4")
    ]
    "test-efd-new-test"                 | true    | [TestSucceedSpock]          | []
    "test-efd-new-parameterized-test"   | true    | [TestParameterizedSpock]    | []
    "test-efd-known-tests-and-new-test" | true    | [TestParameterizedSpock]    | [new TestFQN("org.example.TestParameterizedSpock", "test add 1 and 2")]
    "test-efd-new-slow-test"            | true    | [TestSucceedSpockSlow]      | [] // is executed only twice
    "test-efd-new-very-slow-test"       | true    | [TestSucceedSpockVerySlow]  | [] // is executed only once
    "test-efd-faulty-session-threshold" | false   | [TestSucceedAndFailedSpock] | []
    "test-efd-skip-new-test"            | true    | [TestSucceedSpockSkipEfd]   | []
  }

  def "test impacted tests detection #testcaseName"() {
    givenImpactedTestsDetectionEnabled(true)
    givenDiff(prDiff)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName            | tests              | prDiff
    "test-succeed"          | [TestSucceedSpock] | LineDiff.EMPTY
    "test-succeed"          | [TestSucceedSpock] | new LineDiff([(DUMMY_SOURCE_PATH): lines()])
    "test-succeed-impacted" | [TestSucceedSpock] | new LineDiff([(DUMMY_SOURCE_PATH): lines(DUMMY_TEST_METHOD_START)])
  }

  def "test quarantined #testcaseName"() {
    givenQuarantinedTests(quarantined)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                            | tests                          | quarantined
    "test-quarantined-failed"               | [TestFailedSpock]              | [new TestFQN("org.example.TestFailedSpock", "test failed")]
    "test-quarantined-failed-parameterized" | [TestFailedParameterizedSpock] | [new TestFQN("org.example.TestFailedParameterizedSpock", "test add 4 and 4")]
  }

  def "test quarantined auto-retries #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenFlakyRetryEnabled(true)
    givenFlakyTests(retried)

    // every test retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                  | tests             | quarantined                                                 | retried
    "test-quarantined-failed-atr" | [TestFailedSpock] | [new TestFQN("org.example.TestFailedSpock", "test failed")] | [new TestFQN("org.example.TestFailedSpock", "test failed")]
  }

  def "test quarantined early flakiness detection #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(known)

    // every retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                    | tests             | quarantined                                                 | known
    "test-quarantined-failed-known" | [TestFailedSpock] | [new TestFQN("org.example.TestFailedSpock", "test failed")] | [new TestFQN("org.example.TestFailedSpock", "test failed")]
    "test-quarantined-failed-efd"   | [TestFailedSpock] | [new TestFQN("org.example.TestFailedSpock", "test failed")] | []
  }

  def "test disabled #testcaseName"() {
    givenDisabledTests(disabled)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                         | tests                          | disabled
    "test-disabled-failed"               | [TestFailedSpock]              | [new TestFQN("org.example.TestFailedSpock", "test failed")]
    "test-disabled-failed-parameterized" | [TestFailedParameterizedSpock] | [new TestFQN("org.example.TestFailedParameterizedSpock", "test add 4 and 4")]
  }

  def "test attempt to fix #testcaseName"() {
    givenQuarantinedTests(quarantined)
    givenDisabledTests(disabled)
    givenAttemptToFixTests(attemptToFix)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                | success | tests              | attemptToFix                                                  | quarantined                                                   | disabled
    "test-attempt-to-fix-failed"                | false   | [TestFailedSpock]  | [new TestFQN("org.example.TestFailedSpock", "test failed")]   | []                                                            | []
    "test-attempt-to-fix-succeeded"             | true    | [TestSucceedSpock] | [new TestFQN("org.example.TestSucceedSpock", "test success")] | []                                                            | []
    "test-attempt-to-fix-quarantined-failed"    | true    | [TestFailedSpock]  | [new TestFQN("org.example.TestFailedSpock", "test failed")]   | [new TestFQN("org.example.TestFailedSpock", "test failed")]   | []
    "test-attempt-to-fix-quarantined-succeeded" | true    | [TestSucceedSpock] | [new TestFQN("org.example.TestSucceedSpock", "test success")] | [new TestFQN("org.example.TestSucceedSpock", "test success")] | []
    "test-attempt-to-fix-disabled-failed"       | true    | [TestFailedSpock]  | [new TestFQN("org.example.TestFailedSpock", "test failed")]   | []                                                            | [new TestFQN("org.example.TestFailedSpock", "test failed")]
    "test-attempt-to-fix-disabled-succeeded"    | true    | [TestSucceedSpock] | [new TestFQN("org.example.TestSucceedSpock", "test success")] | []                                                            | [new TestFQN("org.example.TestSucceedSpock", "test success")]
  }

  def "test capabilities tagging #testcaseName"() {
    setup:
    runTests([TestSucceedSpock], true)

    expect:
    assertCapabilities(JUnitPlatformUtils.SPOCK_CAPABILITIES, 4)
  }

  private static void runTests(List<Class<?>> classes, boolean expectSuccess = true) {
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
