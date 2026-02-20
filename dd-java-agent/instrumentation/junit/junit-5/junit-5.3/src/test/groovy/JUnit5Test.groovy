import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

import datadog.environment.JavaVirtualMachine
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.example.TestAssumption
import org.example.TestAssumptionAndSucceed
import org.example.TestAssumptionLegacy

import org.example.TestError
import org.example.TestFactory
import org.example.TestFailed
import org.example.TestFailedAndSucceed
import org.example.TestFailedFactory
import org.example.TestFailedParameterized
import org.example.TestFailedSuiteSetup
import org.example.TestFailedSuiteTearDown
import org.example.TestFailedTemplate
import org.example.TestFailedThenSucceed
import org.example.TestInheritance
import org.example.TestParameterized
import org.example.TestRepeated
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSkippedNested
import org.example.TestSucceed
import org.example.TestSucceedAndSkipped
import org.example.TestSucceedExpectedException
import org.example.TestSucceedNested
import org.example.TestSucceedSkipEfd
import org.example.TestSucceedSlow
import org.example.TestSucceedUnskippable
import org.example.TestSucceedUnskippableSuite
import org.example.TestSucceedVerySlow
import org.example.TestSucceedWithCategories
import org.example.TestSuiteSetUpAssumption
import org.example.TestTemplate
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import spock.lang.IgnoreIf

@IgnoreIf(reason = """
Oracle JDK 1.8 did not merge the fix in JDK-8058322, leading to the JVM failing to correctly 
extract method parameters without args, when the code is compiled on a later JDK (targeting 8). 
This can manifest when creating mocks.
""", value = {
  JavaVirtualMachine.isOracleJDK8()
})
@DisableTestTrace(reason = "avoid self-tracing")
class JUnit5Test extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                         | success | tests
    "test-succeed"                                       | true    | [TestSucceed]
    "test-inheritance"                                   | true    | [TestInheritance]
    "test-parameterized${version()}"                     | true    | [TestParameterized]
    "test-repeated"                                      | true    | [TestRepeated]
    "test-template"                                      | true    | [TestTemplate]
    "test-factory"                                       | true    | [TestFactory]
    "test-failed"                                        | false   | [TestFailed]
    "test-error"                                         | false   | [TestError]
    "test-skipped"                                       | true    | [TestSkipped]
    "test-skipped-class"                                 | true    | [TestSkippedClass]
    "test-assumption-failed"                             | true    | [TestAssumption]
    "test-assumption-failed-legacy"                      | true    | [TestAssumptionLegacy]
    "test-succeed-and-skipped"                           | true    | [TestSucceedAndSkipped]
    "test-succeed-and-failed"                            | false   | [TestFailedAndSucceed]
    "test-suite-teardown-failed"                         | false   | [TestFailedSuiteTearDown]
    "test-suite-setup-failed"                            | false   | [TestFailedSuiteSetup]
    "test-categories"                                    | true    | [TestSucceedWithCategories]
    "test-suite-setup-assumption-failed"                 | true    | [TestSuiteSetUpAssumption]
    "test-suite-setup-assumption-failed-multi-test-case" | true    | [TestAssumptionAndSucceed]
    "test-succeed-multiple-suites"                       | true    | [TestSucceed, TestSucceedAndSkipped]
    "test-succeed-and-failed-multiple-suites"            | false   | [TestSucceed, TestFailedAndSucceed]
    "test-succeed-nested-suites"                         | true    | [TestSucceedNested]
    "test-skipped-nested-suites"                         | true    | [TestSkippedNested]
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                                 | tests                         | skippedTests
    "test-itr-skipping"                          | [TestFailedAndSucceed]        | [
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_another_succeed", null),
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_failed", null)
    ]
    "test-itr-skipping-parametrized${version()}" | [TestParameterized]           | [
      new TestIdentifier("org.example.TestParameterized", "test_parameterized", '{"metadata":{"test_name":"[1] 0, 0, 0, some:\\\"parameter\\\""}}')
    ]
    "test-itr-unskippable"                       | [TestSucceedUnskippable]      | [new TestIdentifier("org.example.TestSucceedUnskippable", "test_succeed", null)]
    "test-itr-unskippable-suite"                 | [TestSucceedUnskippableSuite] | [new TestIdentifier("org.example.TestSucceedUnskippableSuite", "test_succeed", null)]
    "test-itr-unskippable-not-skipped"           | [TestSucceedUnskippable]      | []
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                             | success | tests                          | retriedTests
    "test-failed"                            | false   | [TestFailed]                   | []
    "test-retry-failed"                      | false   | [TestFailed]                   | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-failed-then-succeed"               | true    | [TestFailedThenSucceed]        | [new TestFQN("org.example.TestFailedThenSucceed", "test_failed_then_succeed")]
    "test-retry-template"                    | false   | [TestFailedTemplate]           | [new TestFQN("org.example.TestFailedTemplate", "test_template")]
    "test-retry-factory"                     | false   | [TestFailedFactory]            | [new TestFQN("org.example.TestFailedFactory", "test_factory")]
    "test-assumption-is-not-retried"         | true    | [TestAssumption]               | [new TestFQN("org.example.TestAssumption", "test_fail_assumption")]
    "test-skipped-is-not-retried"            | true    | [TestSkipped]                  | [new TestFQN("org.example.TestSkipped", "test_skipped")]
    "test-retry-parameterized${version()}"   | false   | [TestFailedParameterized]      | [new TestFQN("org.example.TestFailedParameterized", "test_failed_parameterized")]
    "test-expected-exception-is-not-retried" | true    | [TestSucceedExpectedException] | [new TestFQN("org.example.TestSucceedExpectedException", "test_succeed")]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                    | success | tests                  | knownTestsList
    "test-efd-known-test"                           | true    | [TestSucceed]          | [new TestFQN("org.example.TestSucceed", "test_succeed")]
    "test-efd-known-parameterized-test${version()}" | true    | [TestParameterized]    | [new TestFQN("org.example.TestParameterized", "test_parameterized")]
    "test-efd-new-test"                             | true    | [TestSucceed]          | []
    "test-efd-new-parameterized-test${version()}"   | true    | [TestParameterized]    | []
    "test-efd-known-tests-and-new-test"             | false   | [TestFailedAndSucceed] | [
      new TestFQN("org.example.TestFailedAndSucceed", "test_failed"),
      new TestFQN("org.example.TestFailedAndSucceed", "test_succeed")
    ]
    "test-efd-new-slow-test"                        | true    | [TestSucceedSlow]      | [] // is executed only twice
    "test-efd-new-very-slow-test"                   | true    | [TestSucceedVerySlow]  | [] // is executed only once
    "test-efd-faulty-session-threshold"             | false   | [TestFailedAndSucceed] | []
    "test-efd-skip-new-test"                        | true    | [TestSucceedSkipEfd]   | []
  }

  def "test impacted tests detection #testcaseName"() {
    givenImpactedTestsDetectionEnabled(true)
    givenDiff(prDiff)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName            | tests         | prDiff
    "test-succeed"          | [TestSucceed] | LineDiff.EMPTY
    "test-succeed"          | [TestSucceed] | new LineDiff([(DUMMY_SOURCE_PATH): lines()])
    "test-succeed-impacted" | [TestSucceed] | new LineDiff([(DUMMY_SOURCE_PATH): lines(DUMMY_TEST_METHOD_START)])
  }

  def "test quarantined #testcaseName"() {
    givenQuarantinedTests(quarantined)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                                        | tests                     | quarantined
    "test-quarantined-failed"                           | [TestFailed]              | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-quarantined-failed-parameterized${version()}" | [TestFailedParameterized] | [new TestFQN("org.example.TestFailedParameterized", "test_failed_parameterized")]
  }

  def "test quarantined auto-retries #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenFlakyRetryEnabled(true)
    givenFlakyTests(retried)

    // every test retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                  | tests        | quarantined                                            | retried
    "test-quarantined-failed-atr" | [TestFailed] | [new TestFQN("org.example.TestFailed", "test_failed")] | [new TestFQN("org.example.TestFailed", "test_failed")]
  }

  def "test quarantined early flakiness detection #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(known)

    // every retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                    | tests        | quarantined                                            | known
    "test-quarantined-failed-known" | [TestFailed] | [new TestFQN("org.example.TestFailed", "test_failed")] | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-quarantined-failed-efd"   | [TestFailed] | [new TestFQN("org.example.TestFailed", "test_failed")] | []
  }

  def "test disabled #testcaseName"() {
    givenDisabledTests(disabled)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                                     | tests                     | disabled
    "test-disabled-failed"                           | [TestFailed]              | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-disabled-failed-parameterized${version()}" | [TestFailedParameterized] | [new TestFQN("org.example.TestFailedParameterized", "test_failed_parameterized")]
  }

  def "test attempt to fix #testcaseName"() {
    givenQuarantinedTests(quarantined)
    givenDisabledTests(disabled)
    givenAttemptToFixTests(attemptToFix)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                | success | tests         | attemptToFix                                             | quarantined                                              | disabled
    "test-attempt-to-fix-failed"                | false   | [TestFailed]  | [new TestFQN("org.example.TestFailed", "test_failed")]   | []                                                       | []
    "test-attempt-to-fix-succeeded"             | true    | [TestSucceed] | [new TestFQN("org.example.TestSucceed", "test_succeed")] | []                                                       | []
    "test-attempt-to-fix-quarantined-failed"    | true    | [TestFailed]  | [new TestFQN("org.example.TestFailed", "test_failed")]   | [new TestFQN("org.example.TestFailed", "test_failed")]   | []
    "test-attempt-to-fix-quarantined-succeeded" | true    | [TestSucceed] | [new TestFQN("org.example.TestSucceed", "test_succeed")] | [new TestFQN("org.example.TestSucceed", "test_succeed")] | []
    "test-attempt-to-fix-disabled-failed"       | true    | [TestFailed]  | [new TestFQN("org.example.TestFailed", "test_failed")]   | []                                                       | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-attempt-to-fix-disabled-succeeded"    | true    | [TestSucceed] | [new TestFQN("org.example.TestSucceed", "test_succeed")] | []                                                       | [new TestFQN("org.example.TestSucceed", "test_succeed")]
  }

  def "test capabilities tagging #testcaseName"() {
    setup:
    Assumptions.assumeTrue(!JUnitPlatformUtils.isJunitTestOrderingSupported(instrumentedLibraryVersion()))
    runTests([TestSucceed], true)

    expect:
    assertCapabilities(JUnitPlatformUtils.JUNIT_CAPABILITIES_BASE, 4)
  }

  protected void runTests(List<Class<?>> tests, boolean expectSuccess = true) {
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

  private static String version() {
    def version = JupiterTestEngine.package.getImplementationVersion()
    return version.startsWith("6") ? "-6" : ""
  }

  @Override
  String instrumentedLibraryName() {
    return "junit5"
  }

  @Override
  String instrumentedLibraryVersion() {
    return JupiterTestEngine.getPackage().getImplementationVersion()
  }

  private static final class TestResultListener implements TestExecutionListener {
    private final Map<TestExecutionResult.Status, Collection<org.junit.platform.launcher.TestIdentifier>> testsByStatus = new ConcurrentHashMap<>()

    void executionFinished(org.junit.platform.launcher.TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
      testsByStatus.computeIfAbsent(testExecutionResult.status, k -> new CopyOnWriteArrayList<>()).add(testIdentifier)
    }
  }
}
