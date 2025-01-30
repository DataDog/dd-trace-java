import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.FileDiff
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import junit.runner.Version
import org.example.TestAssumption
import org.example.TestAssumptionAndSucceed
import org.example.TestError
import org.example.TestFailed
import org.example.TestFailedAndSucceed
import org.example.TestFailedParameterized
import org.example.TestFailedSuiteSetUpAssumption
import org.example.TestFailedSuiteSetup
import org.example.TestFailedSuiteTearDown
import org.example.TestFailedThenSucceed
import org.example.TestInheritance
import org.example.TestParameterized
import org.example.TestParameterizedJUnitParams
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSucceed
import org.example.TestSucceedAndSkipped
import org.example.TestSucceedExpectedException
import org.example.TestSucceedKotlin
import org.example.TestSucceedLegacy
import org.example.TestSucceedParameterizedKotlin
import org.example.TestSucceedSlow
import org.example.TestSucceedSuite
import org.example.TestSucceedUnskippable
import org.example.TestSucceedUnskippableSuite
import org.example.TestSucceedVerySlow
import org.example.TestSucceedWithCategories
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit4Test extends CiVisibilityInstrumentationTest {

  def runner = new JUnitCore()

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                                         | tests
    "test-succeed"                                       | [TestSucceed]
    "test-inheritance"                                   | [TestInheritance]
    "test-failed"                                        | [TestFailed]
    "test-error"                                         | [TestError]
    "test-skipped"                                       | [TestSkipped]
    "test-class-skipped"                                 | [TestSkippedClass]
    "test-success-and-skipped"                           | [TestSucceedAndSkipped]
    "test-success-and-failure"                           | [TestFailedAndSucceed]
    "test-suite-teardown-failure"                        | [TestFailedSuiteTearDown]
    "test-suite-setup-failure"                           | [TestFailedSuiteSetup]
    "test-assumption-failure"                            | [TestAssumption]
    "test-categories-are-included-in-spans"              | [TestSucceedWithCategories]
    "test-assumption-failure-during-suite-setup"         | [TestFailedSuiteSetUpAssumption]
    "test-assumption-failure-in-a-multi-test-case-suite" | [TestAssumptionAndSucceed]
    "test-multiple-successful-suites"                    | [TestSucceed, TestSucceedAndSkipped]
    "test-successful-suite-and-failing-suite"            | [TestSucceed, TestFailedAndSucceed]
    "test-parameterized"                                 | [TestParameterized]
    "test-suite-runner"                                  | [TestSucceedSuite]
    "test-legacy"                                        | [TestSucceedLegacy]
    "test-parameterized-junit-params"                    | [TestParameterizedJUnitParams]
    "test-succeed-kotlin"                                | [TestSucceedKotlin]
    "test-succeed-parameterized-kotlin"                  | [TestSucceedParameterizedKotlin]
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

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                             | tests                          | retriedTests
    "test-failed"                            | [TestFailed]                   | []
    "test-retry-failed"                      | [TestFailed]                   | [new TestIdentifier("org.example.TestFailed", "test_failed", null)]
    "test-failed-then-succeed"               | [TestFailedThenSucceed]        | [new TestIdentifier("org.example.TestFailedThenSucceed", "test_failed_then_succeed", null)]
    "test-assumption-is-not-retried"         | [TestAssumption]               | [new TestIdentifier("org.example.TestAssumption", "test_fail_assumption", null)]
    "test-skipped-is-not-retried"            | [TestSkipped]                  | [new TestIdentifier("org.example.TestSkipped", "test_skipped", null)]
    "test-retry-parameterized"               | [TestFailedParameterized]      | [
      new TestIdentifier("org.example.TestFailedParameterized", "test_failed_parameterized", /* backend cannot provide parameters for flaky parameterized tests yet */ null)
    ]
    "test-expected-exception-is-not-retried" | [TestSucceedExpectedException] | [new TestIdentifier("org.example.TestSucceedExpectedException", "test_succeed", null)]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | tests                  | knownTestsList
    "test-efd-known-test"               | [TestSucceed]          | [new TestIdentifier("org.example.TestSucceed", "test_succeed", null)]
    "test-efd-known-parameterized-test" | [TestParameterized]    | [new TestIdentifier("org.example.TestParameterized", "parameterized_test_succeed", null)]
    "test-efd-new-test"                 | [TestSucceed]          | []
    "test-efd-new-parameterized-test"   | [TestParameterized]    | []
    "test-efd-known-tests-and-new-test" | [TestFailedAndSucceed] | [
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_failed", null),
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_succeed", null)
    ]
    "test-efd-new-slow-test"            | [TestSucceedSlow]      | [] // is executed only twice
    "test-efd-new-very-slow-test"       | [TestSucceedVerySlow]  | [] // is executed only once
    "test-efd-faulty-session-threshold" | [TestFailedAndSucceed] | []
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

  private void runTests(Collection<Class<?>> tests) {
    TestEventsHandlerHolder.start()
    try {
      Class[] array = tests.toArray(new Class[0])
      runner.run(array)
    } catch (Throwable ignored) {
      // Ignored
    }
    TestEventsHandlerHolder.stop()
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
