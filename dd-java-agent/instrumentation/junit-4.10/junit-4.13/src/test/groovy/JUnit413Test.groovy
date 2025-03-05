import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit4.JUnit4Utils
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import junit.runner.Version
import org.example.TestFailedAfter
import org.example.TestFailedAfterClass
import org.example.TestFailedAfterParam
import org.example.TestFailedBefore
import org.example.TestFailedBeforeClass
import org.example.TestFailedBeforeParam
import org.example.TestSucceedBeforeAfter
import org.example.TestSucceedBeforeClassAfterClass
import org.example.TestSucceedBeforeParamAfterParam
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit413Test extends CiVisibilityInstrumentationTest {

  def runner = new JUnitCore()

  def "test #testcaseName"() {
    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                            | success | tests
    "test-succeed-before-after"             | true    | [TestSucceedBeforeAfter]
    "test-succeed-before-class-after-class" | true    | [TestSucceedBeforeClassAfterClass]
    "test-succeed-before-param-after-param" | true    | [TestSucceedBeforeParamAfterParam]
    "test-failed-before-class"              | false   | [TestFailedBeforeClass]
    "test-failed-after-class"               | false   | [TestFailedAfterClass]
    "test-failed-before"                    | false   | [TestFailedBefore]
    "test-failed-after"                     | false   | [TestFailedAfter]
    "test-failed-before-param"              | false   | [TestFailedBeforeParam]
    "test-failed-after-param"               | false   | [TestFailedAfterParam]
  }

  private void runTests(Collection<Class<?>> tests, boolean expectSuccess = true) {
    TestEventsHandlerHolder.start(TestFrameworkInstrumentation.JUNIT4, JUnit4Utils.CAPABILITIES)
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
    return Version.id()
  }
}
