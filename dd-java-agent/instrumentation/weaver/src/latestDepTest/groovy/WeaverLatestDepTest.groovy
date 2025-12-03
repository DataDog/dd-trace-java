import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.weaver.DatadogWeaverReporter
import datadog.trace.instrumentation.weaver.WeaverIntegrationTestRunner
import datadog.trace.instrumentation.weaver.WeaverUtils
import org.example.TestFailed
import org.example.TestFailedExceptionPure
import org.example.TestFailedPure
import org.example.TestIgnored
import org.example.TestSucceed
import org.example.TestSucceedGlobalResource
import org.example.TestSucceedPure
import org.example.TestSucceedSuiteResource

@DisableTestTrace(reason = "avoid self-tracing")
class WeaverLatestDepTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                   | tests
    "test-succeed-pure"            | [TestSucceedPure]
    "test-failed-pure"             | [TestFailedPure]
    "test-failed-exception-pure"   | [TestFailedExceptionPure]
    "test-succeeded"               | [TestSucceed]
    "test-failed"                  | [TestFailed]
    "test-ignored"                 | [TestIgnored]
    "test-succeed-suite-resource"  | [TestSucceedSuiteResource]
    "test-succeed-global-resource" | [TestSucceedGlobalResource]
  }

  def "test capabilities tagging"() {
    setup:
    runTests([TestSucceed])

    expect:
    assertCapabilities(WeaverUtils.CAPABILITIES, 4)
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
