package datadog.trace.instrumentation.testng;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import datadog.trace.util.MurmurHash2;
import lombok.extern.slf4j.Slf4j;
import org.testng.ITestResult;

@Slf4j
public class TestNGDecorator extends TestDecorator {
  public static final TestNGDecorator DECORATE = new TestNGDecorator();

  @Override
  public String testFramework() {
    return "testng";
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"testng"};
  }

  @Override
  public String component() {
    return "testng";
  }

  public void onTestStart(final AgentSpan span, final ITestResult result) {
    final String testSuite = result.getInstanceName();
    final String testName =
        (result.getTestName() != null) ? result.getTestName() : result.getMethod().getMethodName();

    span.setTag(DDTags.RESOURCE_NAME, testSuite + "." + testName);
    span.setTag(Tags.TEST_SUITE, testSuite);
    span.setTag(Tags.TEST_NAME, testName);
    span.setTag(Tags.TEST_FINGERPRINT, MurmurHash2.hash64(testSuite + "." + testName));
  }

  public void onTestSuccess(final AgentSpan span) {
    span.setTag(Tags.TEST_STATUS, TEST_PASS);
  }

  public void onTestFailure(final AgentSpan span, final ITestResult result) {
    final Throwable throwable = result.getThrowable();
    if (throwable != null) {
      span.addThrowable(throwable);
    }

    span.setError(true);
    span.setTag(Tags.TEST_STATUS, TEST_FAIL);
  }

  public void onTestIgnored(final AgentSpan span, final ITestResult result) {
    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    // Typically the way of skipping a TestNG test is throwing a SkipException
    if (result.getThrowable() != null) {
      span.setTag(Tags.TEST_SKIP_REASON, result.getThrowable().getMessage());
    }
  }
}
