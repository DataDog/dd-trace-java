import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.FileDiff
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import junit.runner.Version
import org.example.*
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
    "test-retry-failed"                      | false   | [TestFailed]                   | [new TestIdentifier("org.example.TestFailed", "test_failed", null)]
    "test-failed-then-succeed"               | true    | [TestFailedThenSucceed]        | [new TestIdentifier("org.example.TestFailedThenSucceed", "test_failed_then_succeed", null)]
    "test-assumption-is-not-retried"         | true    | [TestAssumption]               | [new TestIdentifier("org.example.TestAssumption", "test_fail_assumption", null)]
    "test-skipped-is-not-retried"            | true    | [TestSkipped]                  | [new TestIdentifier("org.example.TestSkipped", "test_skipped", null)]
    "test-retry-parameterized"               | false   | [TestFailedParameterized]      | [
      new TestIdentifier("org.example.TestFailedParameterized", "test_failed_parameterized", /* backend cannot provide parameters for flaky parameterized tests yet */ null)
    ]
    "test-expected-exception-is-not-retried" | true    | [TestSucceedExpectedException] | [new TestIdentifier("org.example.TestSucceedExpectedException", "test_succeed", null)]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | success | tests                  | knownTestsList
    "test-efd-known-test"               | true    | [TestSucceed]          | [new TestIdentifier("org.example.TestSucceed", "test_succeed", null)]
    "test-efd-known-parameterized-test" | true    | [TestParameterized]    | [new TestIdentifier("org.example.TestParameterized", "parameterized_test_succeed", null)]
    "test-efd-new-test"                 | true    | [TestSucceed]          | []
    "test-efd-new-parameterized-test"   | true    | [TestParameterized]    | []
    "test-efd-known-tests-and-new-test" | false   | [TestFailedAndSucceed] | [
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_failed", null),
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_succeed", null)
    ]
    "test-efd-new-slow-test"            | true    | [TestSucceedSlow]      | [] // is executed only twice
    "test-efd-new-very-slow-test"       | true    | [TestSucceedVerySlow]  | [] // is executed only once
    "test-efd-faulty-session-threshold" | false   | [TestFailedAndSucceed] | []
  }

  def "test impacted tests detection #testcaseName"() {
    givenImpactedTestsDetectionEnabled(true)
    givenDiff(prDiff)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName            | tests         | prDiff
    "test-succeed"          | [TestSucceed] | LineDiff.EMPTY
    "test-succeed"          | [TestSucceed] | new FileDiff(new HashSet())
    "test-succeed-impacted" | [TestSucceed] | new FileDiff(new HashSet([DUMMY_SOURCE_PATH]))
    "test-succeed"          | [TestSucceed] | new LineDiff([(DUMMY_SOURCE_PATH): lines()])
    "test-succeed-impacted" | [TestSucceed] | new LineDiff([(DUMMY_SOURCE_PATH): lines(DUMMY_TEST_METHOD_START)])
  }

  def "test quarantined #testcaseName"() {
    givenTestManagementEnabled(true)
    givenQuarantinedTests(quarantined)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                            | tests                     | quarantined
    "test-quarantined-failed"               | [TestFailed]              | [new TestIdentifier("org.example.TestFailed", "test_failed", null)]
    "test-quarantined-failed-parameterized" | [TestFailedParameterized] | [new TestIdentifier("org.example.TestFailedParameterized", "test_failed_parameterized", null)]
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
    testcaseName                  | tests        | quarantined                                                         | retried
    "test-quarantined-failed-atr" | [TestFailed] | [new TestIdentifier("org.example.TestFailed", "test_failed", null)] | [new TestIdentifier("org.example.TestFailed", "test_failed", null)]
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
    testcaseName                    | tests        | quarantined                                                         | known
    "test-quarantined-failed-known" | [TestFailed] | [new TestIdentifier("org.example.TestFailed", "test_failed", null)] | [new TestIdentifier("org.example.TestFailed", "test_failed", null)]
    "test-quarantined-failed-efd"   | [TestFailed] | [new TestIdentifier("org.example.TestFailed", "test_failed", null)] | []
  }

  private void runTests(Collection<Class<?>> tests, boolean expectSuccess = true) {
    TestEventsHandlerHolder.start()
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
      TestEventsHandlerHolder.stop()
    }
  }

  @Override
  String instrumentedLibraryName() {
    return "junit4"
  }

  @Override
  String instrumentedLibraryVersion() {
    return Version.id()
  }
}
