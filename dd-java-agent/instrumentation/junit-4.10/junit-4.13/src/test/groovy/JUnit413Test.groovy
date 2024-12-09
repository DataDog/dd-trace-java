import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
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
    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                            | tests                              | expectedTracesCount
    "test-succeed-before-after"             | [TestSucceedBeforeAfter]           | 3
    "test-succeed-before-class-after-class" | [TestSucceedBeforeClassAfterClass] | 3
    "test-succeed-before-param-after-param" | [TestSucceedBeforeParamAfterParam] | 2
    "test-failed-before-class"              | [TestFailedBeforeClass]            | 1
    "test-failed-after-class"               | [TestFailedAfterClass]             | 3
    "test-failed-before"                    | [TestFailedBefore]                 | 3
    "test-failed-after"                     | [TestFailedAfter]                  | 3
    "test-failed-before-param"              | [TestFailedBeforeParam]            | 2
    "test-failed-after-param"               | [TestFailedAfterParam]             | 2
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
