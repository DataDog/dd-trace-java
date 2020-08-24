package datadog.trace.instrumentation.testng;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
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
    span.setTag(DDTags.TEST_SUITE, testSuite);
    span.setTag(DDTags.TEST_NAME, testName);
  }

  public void onTestSuccess(final AgentSpan span) {
    span.setTag(DDTags.TEST_STATUS, TEST_PASS);
  }

  public void onTestFailure(final AgentSpan span, final ITestResult result) {
    final Throwable throwable = result.getThrowable();
    if (throwable != null) {
      span.addThrowable(throwable);
    }

    span.setError(true);
    span.setTag(DDTags.TEST_STATUS, TEST_FAIL);
  }

  public void onTestIgnored(final AgentSpan span, final ITestResult result) {
    span.setTag(DDTags.TEST_STATUS, TEST_SKIP);
  }
}
