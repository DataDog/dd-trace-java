import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.civisibility.CiVisibilityTest
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import org.example.TestFailedAssumptionMUnit
import org.example.TestSkippedMUnit
import org.example.TestSkippedSuiteMUnit
import org.example.TestSucceedMUnit
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class MUnitTest extends CiVisibilityTest {

  def runner = new JUnitCore()

  def "test success generates spans"() {
    setup:
    runTests(TestSucceedMUnit)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceedMUnit", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSucceedMUnit", "Calculator.add", null, CIConstants.TEST_PASS, null, null, false, ["myTag"], true, false)
      }
    })
  }

  def "test skipped generates spans"() {
    setup:
    runTests(TestSkippedMUnit)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSkippedMUnit", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSkippedMUnit", "Calculator.add", null, CIConstants.TEST_SKIP, null, null, false, ["Ignore"], true, false)
      }
    })
  }

  def "test skipped suite generates spans"() {
    setup:
    runTests(TestSkippedSuiteMUnit)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSkippedSuiteMUnit", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSkippedSuiteMUnit", "Calculator.add", null, CIConstants.TEST_SKIP, null, null, false, ["Ignore"], true, false)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSkippedSuiteMUnit", "Calculator.subtract", null, CIConstants.TEST_SKIP, null, null, false, ["Ignore"], true, false)
      }
    })
  }

  def "test failed assumption generates spans"() {
    setup:
    runTests(TestFailedAssumptionMUnit)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestFailedAssumptionMUnit", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailedAssumptionMUnit", "Calculator.add", null, CIConstants.TEST_PASS, null, null, false, null, true, false)
      }
    })
  }

  private void runTests(Class<?>... tests) {
    TestEventsHandlerHolder.start()
    runner.run(tests)
    TestEventsHandlerHolder.stop()
  }

  @Override
  String expectedOperationPrefix() {
    return "junit"
  }

  @Override
  String expectedTestFramework() {
    return "munit"
  }

  @Override
  String expectedTestFrameworkVersion() {
    return "0.7.28"
  }

  @Override
  String component() {
    return "junit"
  }
}
