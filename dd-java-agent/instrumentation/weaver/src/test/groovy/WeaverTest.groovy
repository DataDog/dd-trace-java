import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.weaver.WeaverInstrumentationTestRunner
import datadog.trace.instrumentation.weaver.WeaverUtils
import org.example.TestCanceled
import org.example.TestFailed
import org.example.TestIgnored
import org.example.TestPureException
import org.example.TestPureFailed
import org.example.TestPureSucceeded
import org.example.TestSucceded

@DisableTestTrace(reason = "avoid self-tracing")
class WeaverTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName          | tests               | expectedTracesCount
    "test-pure-succeeded" | [TestPureSucceeded] | 2
    "test-pure-failed"    | [TestPureFailed]    | 2
    "test-pure-exception" | [TestPureException] | 2
    "test-succeeded"      | [TestSucceded]      | 2
    "test-failed"         | [TestFailed]        | 2
    "test-ignored"        | [TestIgnored]       | 2
    "test-canceled"       | [TestCanceled]      | 2
  }

  @Override
  String instrumentedLibraryName() {
    return "weaver"
  }

  @Override
  String instrumentedLibraryVersion() {
    return WeaverUtils.weaverVersion
  }

  void runTests(List<Class<?>> tests) {
    def testNames = tests.collect { it.name }
    WeaverInstrumentationTestRunner.runTests(testNames)
  }
}
