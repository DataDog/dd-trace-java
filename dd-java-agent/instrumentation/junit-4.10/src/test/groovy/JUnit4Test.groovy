import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.junit4.JUnit4Utils
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import org.example.*
import org.junit.jupiter.api.Assumptions
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit4Test extends CiVisibilityInstrumentationTest {

  def runner = new JUnitCore()

  def "test #testcaseName"() {
    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                         | success | tests
    "test-succeed"                                       | true    | [TestSucceed]
    "test-inheritance"                                   | true    | [TestInheritance]
    "test-failed"                                        | false   | [TestFailed]
    "test-error"                                         | false   | [TestError]
    "test-skipped"                                       | true    | [TestSkipped]
    "test-class-skipped"                                 | true    | [TestSkippedClass]
    "test-success-and-skipped"                           | true    | [TestSucceedAndSkipped]
    "test-success-and-failure"                           | false   | [TestFailedAndSucceed]
    "test-suite-teardown-failure"                        | false   | [TestFailedSuiteTearDown]
    "test-suite-setup-failure"                           | false   | [TestFailedSuiteSetup]
    "test-assumption-failure"                            | true    | [TestAssumption]
    "test-categories-are-included-in-spans"              | true    | [TestSucceedWithCategories]
    "test-assumption-failure-during-suite-setup"         | true    | [TestFailedSuiteSetUpAssumption]
    "test-assumption-failure-in-a-multi-test-case-suite" | true    | [TestAssumptionAndSucceed]
    "test-multiple-successful-suites"                    | true    | [TestSucceed, TestSucceedAndSkipped]
    "test-successful-suite-and-failing-suite"            | false   | [TestSucceed, TestFailedAndSucceed]
    "test-parameterized"                                 | true    | [TestParameterized]
    "test-suite-runner"                                  | true    | [TestSucceedSuite]
    "test-legacy"                                        | true    | [TestSucceedLegacy]
    "test-parameterized-junit-params"                    | true    | [TestParameterizedJUnitParams]
    "test-succeed-kotlin"                                | true    | [TestSucceedKotlin]
    "test-succeed-parameterized-kotlin"                  | true    | [TestSucceedParameterizedKotlin]
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                       | tests                         | skippedTests
    "test-itr-skipping"                | [TestFailedAndSucceed]        | [
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_another_succeed", null),
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_failed", null)
    ]
    "test-itr-skipping-parameterized"  | [TestParameterized]           | [
      new TestIdentifier("org.example.TestParameterized", "parameterized_test_succeed", '{"metadata":{"test_name":"parameterized_test_succeed[str1]"}}')
    ]
    "test-itr-unskippable"             | [TestSucceedUnskippable]      | [new TestIdentifier("org.example.TestSucceedUnskippable", "test_succeed", null)]
    "test-itr-unskippable-suite"       | [TestSucceedUnskippableSuite] | [new TestIdentifier("org.example.TestSucceedUnskippableSuite", "test_succeed", null)]
    "test-itr-unskippable-not-skipped" | [TestSucceedUnskippable]      | []
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
    "test-assumption-is-not-retried"         | true    | [TestAssumption]               | [new TestFQN("org.example.TestAssumption", "test_fail_assumption")]
    "test-skipped-is-not-retried"            | true    | [TestSkipped]                  | [new TestFQN("org.example.TestSkipped", "test_skipped")]
    "test-retry-parameterized"               | false   | [TestFailedParameterized]      | [
      new TestFQN("org.example.TestFailedParameterized", "test_failed_parameterized") /* backend cannot provide parameters for flaky parameterized tests yet */
    ]
    "test-expected-exception-is-not-retried" | true    | [TestSucceedExpectedException] | [new TestFQN("org.example.TestSucceedExpectedException", "test_succeed")]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | success | tests                  | knownTestsList
    "test-efd-known-test"               | true    | [TestSucceed]          | [new TestFQN("org.example.TestSucceed", "test_succeed")]
    "test-efd-known-parameterized-test" | true    | [TestParameterized]    | [new TestFQN("org.example.TestParameterized", "parameterized_test_succeed")]
    "test-efd-new-test"                 | true    | [TestSucceed]          | []
    "test-efd-new-parameterized-test"   | true    | [TestParameterized]    | []
    "test-efd-known-tests-and-new-test" | false   | [TestFailedAndSucceed] | [
      new TestFQN("org.example.TestFailedAndSucceed", "test_failed"),
      new TestFQN("org.example.TestFailedAndSucceed", "test_succeed")
    ]
    "test-efd-new-slow-test"            | true    | [TestSucceedSlow]      | [] // is executed only twice
    "test-efd-new-very-slow-test"       | true    | [TestSucceedVerySlow]  | [] // is executed only once
    "test-efd-faulty-session-threshold" | false   | [TestFailedAndSucceed] | []
    "test-efd-skip-new-test"            | true    | [TestSucceedSkipEfd]   | []
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
    testcaseName                            | tests                     | quarantined
    "test-quarantined-failed"               | [TestFailed]              | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-quarantined-failed-parameterized" | [TestFailedParameterized] | [new TestFQN("org.example.TestFailedParameterized", "test_failed_parameterized")]
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
    testcaseName                         | tests                     | disabled
    "test-disabled-failed"               | [TestFailed]              | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-disabled-failed-parameterized" | [TestFailedParameterized] | [new TestFQN("org.example.TestFailedParameterized", "test_failed_parameterized")]
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
    Assumptions.assumeFalse(JUnit4Utils.isTestOrderingSupported(JUnit4Utils.getVersion()))
    runTests([TestSucceed], true)

    expect:
    assertCapabilities(JUnit4Utils.BASE_CAPABILITIES, 4)
  }

  private void runTests(Collection<Class<?>> tests, boolean expectSuccess = true) {
    TestEventsHandlerHolder.start(TestFrameworkInstrumentation.JUNIT4, JUnit4Utils.capabilities(false))
    try {
      Class[] array = tests.toArray(new Class[0])
      def result = runner.run(array)
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
      TestEventsHandlerHolder.stop(TestFrameworkInstrumentation.JUNIT4)
    }
  }

  @Override
  String instrumentedLibraryName() {
    return "junit4"
  }

  @Override
  String instrumentedLibraryVersion() {
    return JUnit4Utils.getVersion()
  }
}
