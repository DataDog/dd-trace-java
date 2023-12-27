import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit4.MUnitTracingListener
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import org.example.TestFailedAssumptionMUnit
import org.example.TestSkippedMUnit
import org.example.TestSkippedSuiteMUnit
import org.example.TestSucceedMUnit
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class MUnitTest extends CiVisibilityInstrumentationTest {

  def runner = new JUnitCore()

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                          | tests                       | expectedTracesCount
    "test-succeed"                        | [TestSucceedMUnit]          | 2
    "test-skipped"                        | [TestSkippedMUnit]          | 2
    "test-skipped-suite"                  | [TestSkippedSuiteMUnit]     | 3
    "test-failed-assumption-${version()}" | [TestFailedAssumptionMUnit] | 2
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
