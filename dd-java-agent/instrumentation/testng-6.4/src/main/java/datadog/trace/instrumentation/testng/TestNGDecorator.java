package datadog.trace.instrumentation.testng;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import datadog.trace.util.Strings;
import org.testng.ITestResult;

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

    span.setResourceName(testSuite + "." + testName);
    span.setTag(Tags.TEST_SUITE, testSuite);
    span.setTag(Tags.TEST_NAME, testName);

    if (hasParameters(result)) {
      span.setTag(Tags.TEST_PARAMETERS, buildParametersTagValue(result));
    }
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

  private boolean hasParameters(final ITestResult result) {
    return result.getParameters() != null && result.getParameters().length > 0;
  }

  // We build manually the JSON for test.parameters tag.
  // Example: {"arguments":{"0":"param1","1":"param2"}}
  private String buildParametersTagValue(final ITestResult result) {
    final StringBuilder sb = new StringBuilder("{\"arguments\":{");
    for (int i = 0; i < result.getParameters().length; i++) {
      sb.append("\"")
          .append(i)
          .append("\":\"")
          .append(Strings.escapeToJson(String.valueOf(result.getParameters()[i])))
          .append("\"");
      if (i != result.getParameters().length - 1) {
        sb.append(",");
      }
    }
    sb.append("}}");
    return sb.toString();
  }
}
