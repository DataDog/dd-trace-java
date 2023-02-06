package datadog.trace.instrumentation.testng;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import java.lang.reflect.Method;
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

  public void onTestStart(final AgentSpan span, String version, final ITestResult result) {
    final String testSuiteName = result.getInstanceName();
    final String testName =
        (result.getTestName() != null) ? result.getTestName() : result.getMethod().getMethodName();
    final String testParameters = TestNGUtils.getParameters(result);

    final Class<?> testClass = TestNGUtils.getTestClass(result);
    final Method testMethod = TestNGUtils.getTestMethod(result);

    afterTestStart(
        span, testSuiteName, testName, testParameters, version, testClass, testMethod, null);
  }

  public void onTestSuccess(final AgentSpan span, final ITestResult result) {
    span.setTag(Tags.TEST_STATUS, TEST_PASS);

    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    beforeTestFinish(span, testSuiteName, testClass);
  }

  public void onTestFailure(final AgentSpan span, final ITestResult result) {
    final Throwable throwable = result.getThrowable();
    if (throwable != null) {
      span.addThrowable(throwable);
    }

    span.setError(true);
    span.setTag(Tags.TEST_STATUS, TEST_FAIL);

    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    beforeTestFinish(span, testSuiteName, testClass);
  }

  public void onTestIgnored(final AgentSpan span, final ITestResult result) {
    // Typically the way of skipping a TestNG test is throwing a SkipException
    final Throwable throwable = result.getThrowable();
    if (throwable != null) {
      span.setTag(Tags.TEST_SKIP_REASON, throwable.getMessage());
    }

    span.setTag(Tags.TEST_STATUS, TEST_SKIP);

    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    beforeTestFinish(span, testSuiteName, testClass);
  }
}
