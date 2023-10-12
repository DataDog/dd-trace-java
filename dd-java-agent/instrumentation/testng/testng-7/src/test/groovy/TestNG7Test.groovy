import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.testng.TestNGTest
import datadog.trace.instrumentation.testng.TracingListener

class TestNG7Test extends TestNGTest {

  @Override
  String assertionErrorMessage() {
    // error message format differs based on framework version
    TracingListener.FRAMEWORK_VERSION >= "7.5"
      ? "expected [true] but found [false]"
      : "did not expect to find [true] but found [false]"
  }

  @Override
  String classSkipReason() {
    "Ignore reason in class"
  }

  @Override
  Map<String, String> testCaseTagsIfSuiteSetUpFailedOrSkipped(String skipReason) {
    return [(Tags.TEST_SKIP_REASON): skipReason]
  }
}
