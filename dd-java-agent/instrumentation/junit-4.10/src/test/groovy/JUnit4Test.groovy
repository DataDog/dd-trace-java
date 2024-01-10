import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
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
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSucceed
import org.example.TestSucceedAndSkipped
import org.example.TestSucceedExpectedException
import org.example.TestSucceedLegacy
import org.example.TestSucceedSuite
import org.example.TestSucceedUnskippable
import org.example.TestSucceedUnskippableSuite
import org.example.TestSucceedWithCategories
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit4Test extends CiVisibilityInstrumentationTest {

  def runner = new JUnitCore()

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                                         | tests                                | expectedTracesCount
    "test-succeed"                                       | [TestSucceed]                        | 2
    "test-inheritance"                                   | [TestInheritance]                    | 2
    "test-failed"                                        | [TestFailed]                         | 2
    "test-error"                                         | [TestError]                          | 2
    "test-skipped"                                       | [TestSkipped]                        | 2
    "test-class-skipped"                                 | [TestSkippedClass]                   | 3
    "test-success-and-skipped"                           | [TestSucceedAndSkipped]              | 3
    "test-success-and-failure"                           | [TestFailedAndSucceed]               | 4
    "test-suite-teardown-failure"                        | [TestFailedSuiteTearDown]            | 1
    "test-suite-setup-failure"                           | [TestFailedSuiteSetup]               | 1
    "test-assumption-failure"                            | [TestAssumption]                     | 2
    "test-categories-are-included-in-spans"              | [TestSucceedWithCategories]          | 2
    "test-assumption-failure-during-suite-setup"         | [TestFailedSuiteSetUpAssumption]     | 2
    "test-assumption-failure-in-a-multi-test-case-suite" | [TestAssumptionAndSucceed]           | 3
    "test-multiple-successful-suites"                    | [TestSucceed, TestSucceedAndSkipped] | 4
    "test-successful-suite-and-failing-suite"            | [TestSucceed, TestFailedAndSucceed]  | 5
    "test-parameterized"                                 | [TestParameterized]                  | 3
    "test-suite-runner"                                  | [TestSucceedSuite]                   | 3
    "test-legacy"                                        | [TestSucceedLegacy]                  | 2
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)

    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                       | tests                         | expectedTracesCount | skippedTests
    "test-itr-skipping"                | [TestFailedAndSucceed]        | 4                   | [
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_another_succeed", null, null),
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_failed", null, null)
    ]
    "test-itr-skipping-parameterized"  | [TestParameterized]           | 3                   | [
      new TestIdentifier("org.example.TestParameterized", "parameterized_test_succeed", '{"metadata":{"test_name":"parameterized_test_succeed[str1]"}}', null)
    ]
    "test-itr-unskippable"             | [TestSucceedUnskippable]      | 2                   | [new TestIdentifier("org.example.TestSucceedUnskippable", "test_succeed", null, null)]
    "test-itr-unskippable-suite"       | [TestSucceedUnskippableSuite] | 2                   | [new TestIdentifier("org.example.TestSucceedUnskippableSuite", "test_succeed", null, null)]
    "test-itr-unskippable-not-skipped" | [TestSucceedUnskippable]      | 2                   | []
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyTests(retriedTests)

    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                             | tests                          | expectedTracesCount | retriedTests
    "test-failed"                            | [TestFailed]                   | 2                   | []
    "test-retry-failed"                      | [TestFailed]                   | 6                   | [new TestIdentifier("org.example.TestFailed", "test_failed", null, null)]
    "test-failed-then-succeed"               | [TestFailedThenSucceed]        | 5                   | [new TestIdentifier("org.example.TestFailedThenSucceed", "test_failed_then_succeed", null, null)]
    "test-assumption-is-not-retried"         | [TestAssumption]               | 2                   | [new TestIdentifier("org.example.TestAssumption", "test_fail_assumption", null, null)]
    "test-skipped-is-not-retried"            | [TestSkipped]                  | 2                   | [new TestIdentifier("org.example.TestSkipped", "test_skipped", null, null)]
    "test-retry-parameterized"               | [TestFailedParameterized]      | 7                   | [
      new TestIdentifier("org.example.TestFailedParameterized", "test_failed_parameterized", /* backend cannot provide parameters for flaky parameterized tests yet */ null, null)
    ]
    "test-expected-exception-is-not-retried" | [TestSucceedExpectedException] | 2 | [new TestIdentifier("org.example.TestSucceedExpectedException", "test_succeed", null, null)]
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
