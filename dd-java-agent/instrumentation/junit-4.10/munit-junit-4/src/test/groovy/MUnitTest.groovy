import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.FileDiff
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.junit4.MUnitTracingListener
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import org.example.TestFailedAssumptionMUnit
import org.example.TestFailedMUnit
import org.example.TestFailedThenSucceedMUnit
import org.example.TestSkippedMUnit
import org.example.TestSucceedMUnitSlow
import org.example.TestSkippedSuiteMUnit
import org.example.TestSucceedMUnit
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class MUnitTest extends CiVisibilityInstrumentationTest {

  def runner = new JUnitCore()

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                          | tests
    "test-succeed"                        | [TestSucceedMUnit]
    "test-skipped"                        | [TestSkippedMUnit]
    "test-skipped-suite"                  | [TestSkippedSuiteMUnit]
    "test-failed-assumption-${version()}" | [TestFailedAssumptionMUnit]
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName               | tests                        | retriedTests
    "test-failed"              | [TestFailedMUnit]            | []
    "test-retry-failed"        | [TestFailedMUnit]            | [new TestIdentifier("org.example.TestFailedMUnit", "Calculator.add", null)]
    "test-failed-then-succeed" | [TestFailedThenSucceedMUnit] | [new TestIdentifier("org.example.TestFailedThenSucceedMUnit", "Calculator.add", null)]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName             | tests                  | knownTestsList
    "test-efd-known-test"    | [TestSucceedMUnit]     | [new TestIdentifier("org.example.TestSucceedMUnit", "Calculator.add", null)]
    "test-efd-new-test"      | [TestSucceedMUnit]     | []
    "test-efd-new-slow-test" | [TestSucceedMUnitSlow] | [] // is executed only twice
  }

  def "test impacted tests detection #testcaseName"() {
    givenImpactedTestsDetectionEnabled(true)
    givenDiff(prDiff)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName            | tests              | prDiff
    "test-succeed"          | [TestSucceedMUnit] | LineDiff.EMPTY
    "test-succeed"          | [TestSucceedMUnit] | new FileDiff(new HashSet())
    "test-succeed-impacted" | [TestSucceedMUnit] | new FileDiff(new HashSet([DUMMY_SOURCE_PATH]))
    "test-succeed"          | [TestSucceedMUnit] | new LineDiff([(DUMMY_SOURCE_PATH): lines()])
    "test-succeed-impacted" | [TestSucceedMUnit] | new LineDiff([(DUMMY_SOURCE_PATH): lines(DUMMY_TEST_METHOD_START)])
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

  String version() {
    MUnitTracingListener.FRAMEWORK_VERSION < "1" ? MUnitTracingListener.FRAMEWORK_VERSION : "latest"
  }

  @Override
  String instrumentedLibraryName() {
    MUnitTracingListener.FRAMEWORK_NAME
  }

  @Override
  String instrumentedLibraryVersion() {
    MUnitTracingListener.FRAMEWORK_VERSION
  }
}
