package datadog.trace.instrumentation.testng;

import static datadog.trace.instrumentation.testng.TestNGDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.civisibility.TestEventsHandler;
import java.lang.reflect.Method;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

public class TracingListener implements ITestListener {

  private final TestEventsHandler testEventsHandler;

  private final String version;

  public TracingListener(final String version) {
    this.version = version;
    testEventsHandler = new TestEventsHandler(DECORATE);
  }

  @Override
  public void onStart(final ITestContext context) {}

  @Override
  public void onFinish(final ITestContext context) {}

  @Override
  public void onTestStart(final ITestResult result) {
    String testSuiteName = result.getInstanceName();
    String testName =
        (result.getTestName() != null) ? result.getTestName() : result.getMethod().getMethodName();
    String testParameters = TestNGUtils.getParameters(result);

    Class<?> testClass = TestNGUtils.getTestClass(result);
    Method testMethod = TestNGUtils.getTestMethod(result);

    // TODO support categories
    testEventsHandler.onTestStart(
        testSuiteName, testName, testParameters, null, version, testClass, testMethod);
  }

  @Override
  public void onTestSuccess(final ITestResult result) {
    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    testEventsHandler.onTestFinish(testSuiteName, testClass);
  }

  @Override
  public void onTestFailure(final ITestResult result) {
    final Throwable throwable = result.getThrowable();
    testEventsHandler.onFailure(throwable);

    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    testEventsHandler.onTestFinish(testSuiteName, testClass);
  }

  @Override
  public void onTestFailedButWithinSuccessPercentage(final ITestResult result) {
    onTestFailure(result);
  }

  @Override
  public void onTestSkipped(final ITestResult result) {
    // Typically the way of skipping a TestNG test is throwing a SkipException
    Throwable throwable = result.getThrowable();
    String reason = throwable != null ? throwable.getMessage() : null;
    testEventsHandler.onSkip(reason);

    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    testEventsHandler.onTestFinish(testSuiteName, testClass);
  }
}
