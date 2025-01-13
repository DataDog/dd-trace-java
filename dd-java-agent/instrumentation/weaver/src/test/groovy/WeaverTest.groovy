import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.weaver.WeaverInstrumentationTestRunner
import datadog.trace.instrumentation.weaver.WeaverUtils
import org.example.CancelTest
import org.example.FailTest
import org.example.IgnoreTest
import org.example.PureExceptionTest
import org.example.PureFailTest
import org.example.PureSucceedTest
import org.example.SucceedTest

@DisableTestTrace(reason = "avoid self-tracing")
class WeaverTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    runTests(tests)

    result = 1

    where:
    testcaseName          | tests               | result
    "test-pure-succeed"   | [PureSucceedTest]   | 1
    "test-pure-fail"      | [PureFailTest]      | 1
    "test-pure-exception" | [PureExceptionTest] | 1
    "test-succeed"        | [SucceedTest]       | 1
    "test-fail"           | [FailTest]          | 1
    "test-ignored"        | [IgnoreTest]        | 1
    "test-canceled"       | [CancelTest]        | 1
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
