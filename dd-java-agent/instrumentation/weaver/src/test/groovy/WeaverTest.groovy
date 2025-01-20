import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.weaver.DatadogWeaverReporter
import datadog.trace.instrumentation.weaver.WeaverIntegrationTestRunner
import datadog.trace.instrumentation.weaver.WeaverUtils
import org.example.TestCanceled
import org.example.TestFailed
import org.example.TestFailedExceptionPure
import org.example.TestFailedPure
import org.example.TestIgnored
import org.example.TestSucceed
import org.example.TestSucceedGlobalResource
import org.example.TestSucceedPure
import org.example.TestSucceedSuiteResource

@DisableTestTrace(reason = "avoid self-tracing")
class WeaverTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                   | tests                       | expectedTracesCount
    "test-succeed-pure"            | [TestSucceedPure]           | 2
    "test-failed-pure"             | [TestFailedPure]            | 2
    "test-failed-exception-pure"   | [TestFailedExceptionPure]   | 2
    "test-succeeded"               | [TestSucceed]               | 2
    "test-failed"                  | [TestFailed]                | 2
    "test-ignored"                 | [TestIgnored]               | 2
    "test-canceled"                | [TestCanceled]              | 2
    "test-succeed-suite-resource"  | [TestSucceedSuiteResource]  | 3
    "test-succeed-global-resource" | [TestSucceedGlobalResource] | 2
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
    DatadogWeaverReporter.start()
    def testNames = tests.collect { it.name }
    WeaverIntegrationTestRunner.runTests(testNames)
    DatadogWeaverReporter.stop()
  }
}
