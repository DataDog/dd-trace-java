import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.testng.TestNGTest

class TestNG7Test extends TestNGTest {

  @Override
  String expectedTestFrameworkVersion() {
    return '7.0.0'
  }

  @Override
  String assertionErrorMessage() {
    "did not expect to find [true] but found [false]"
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
