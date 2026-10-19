import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.weaver.DatadogWeaverReporter
import datadog.trace.instrumentation.weaver.WeaverIntegrationTestRunner
import datadog.trace.instrumentation.weaver.WeaverUtils
import org.example.TestCanceled
import org.example.TestFailed
import org.example.TestAutomationExcluded
import org.example.TestFailedExceptionPure
import org.example.TestFailedPure
import org.example.TestIgnored
import org.example.TestSucceed
import org.example.TestSucceedGlobalResource
import org.example.TestSucceedPure
import org.example.TestSucceedSuiteResource

@DisableTestTrace(reason = "avoid self-tracing")
class WeaverTest extends CiVisibilityInstrumentationTest {

  def "test automation exclusions retain native outcomes and declaration tags"() {
    when:
    runTests([TestAutomationExcluded])

    then:
    def testSpans = TEST_WRITER.toList().flatten().findAll { it.getTag("test.name") == "protected_failure" || it.getTag("test.name") == "protected_skip" }
    testSpans.size() == 2
    def ordinary = TEST_WRITER.toList().flatten().find { it.getTag("test.name") == "ordinary_tagged" }
    ordinary != null
    ordinary.getTag("test.test_management.no_auto_retry") == null
    ordinary.getTag("test.test_management.prevent_failure_suppression") == null
    ordinary.getTag("test.traits") == null
    testSpans.every { it.getTag("test.test_management.no_auto_retry") == true }
    testSpans.every { it.getTag("test.test_management.prevent_failure_suppression") == true }
    testSpans.find { it.getTag("test.name") == "protected_failure" }.getTag("test.status").toString() == "fail"
    testSpans.find { it.getTag("test.name") == "protected_skip" }.getTag("test.status").toString() == "skip"
  }

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
    "test-canceled"                | [TestCanceled]
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
