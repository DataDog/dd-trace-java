import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.CiVisibilityTestUtils
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
    ListWriterAssert.assertTraces(TEST_WRITER, 4, true, new CiVisibilityTestUtils.SortTracesByType(), {
      trace(1) {
        span(0) {
          spanType DDSpanTypes.TEST
          tags(false) {
            areNotPresent([
              DDTags.LIBRARY_CAPABILITIES_TIA,
              DDTags.LIBRARY_CAPABILITIES_EFD,
              DDTags.LIBRARY_CAPABILITIES_ATR,
              DDTags.LIBRARY_CAPABILITIES_IMPACTED_TESTS,
              DDTags.LIBRARY_CAPABILITIES_FAIL_FAST_TEST_ORDER,
              DDTags.LIBRARY_CAPABILITIES_QUARANTINE,
              DDTags.LIBRARY_CAPABILITIES_DISABLED,
              DDTags.LIBRARY_CAPABILITIES_ATTEMPT_TO_FIX
            ])
          }
        }
      }
    })
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
