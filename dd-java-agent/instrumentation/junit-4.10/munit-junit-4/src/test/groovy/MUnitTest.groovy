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

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName               | success | tests                        | retriedTests
    "test-failed"              | false   | [TestFailedMUnit]            | []
    "test-retry-failed"        | false   | [TestFailedMUnit]            | [new TestIdentifier("org.example.TestFailedMUnit", "Calculator.add", null)]
    "test-failed-then-succeed" | true    | [TestFailedThenSucceedMUnit] | [new TestIdentifier("org.example.TestFailedThenSucceedMUnit", "Calculator.add", null)]
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

  def "test quarantined #testcaseName"() {
    givenTestManagementEnabled(true)
    givenQuarantinedTests(quarantined)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName              | tests             | quarantined
    "test-quarantined-failed" | [TestFailedMUnit] | [new TestIdentifier("org.example.TestFailedMUnit", "Calculator.add", null)]
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
    testcaseName                  | tests             | quarantined                                                                 | retried
    "test-quarantined-failed-atr" | [TestFailedMUnit] | [new TestIdentifier("org.example.TestFailedMUnit", "Calculator.add", null)] | [new TestIdentifier("org.example.TestFailedMUnit", "Calculator.add", null)]
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
    testcaseName                     | tests             | quarantined                                                                 | known
    "test-quarantined-failed-known" | [TestFailedMUnit] | [new TestIdentifier("org.example.TestFailedMUnit", "Calculator.add", null)] | [new TestIdentifier("org.example.TestFailedMUnit", "Calculator.add", null)]
    "test-quarantined-failed-efd"    | [TestFailedMUnit] | [new TestIdentifier("org.example.TestFailedMUnit", "Calculator.add", null)] | []
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
