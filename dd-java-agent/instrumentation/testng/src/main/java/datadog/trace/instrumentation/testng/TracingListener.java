package datadog.trace.instrumentation.testng;

import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.instrumentation.testng.retry.RetryAnalyzer;
import java.lang.reflect.Method;
import java.util.List;
import org.testng.IConfigurationListener;
import org.testng.IRetryAnalyzer;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

public class TracingListener extends TestNGClassListener
    implements ITestListener, IConfigurationListener {

  public static final String FRAMEWORK_NAME = "testng";
  public static final String FRAMEWORK_VERSION = TestNGUtils.getTestNGVersion();

  @Override
  public void onStart(final ITestContext context) {
    // ignore
  }

  @Override
  public void onFinish(final ITestContext context) {
    // ignore
  }

  @Override
  protected void onBeforeClass(ITestClass testClass, boolean parallelized) {
    TestSuiteDescriptor suiteDescriptor = TestNGUtils.toSuiteDescriptor(testClass);
    String testSuiteName = testClass.getName();
    Class<?> testSuiteClass = testClass.getRealClass();
    List<String> groups = TestNGUtils.getGroups(testClass);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        suiteDescriptor,
        testSuiteName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        testSuiteClass,
        groups,
        parallelized,
        TestFrameworkInstrumentation.TESTNG);
  }

  @Override
  protected void onAfterClass(ITestClass testClass) {
    TestSuiteDescriptor suiteDescriptor = TestNGUtils.toSuiteDescriptor(testClass);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(suiteDescriptor);
  }

  @Override
  public void onConfigurationSuccess(ITestResult result) {
    // ignore
  }

  @Override
  public void onConfigurationFailure(ITestResult result) {
    // suite setup or suite teardown failed
    TestSuiteDescriptor suiteDescriptor =
        TestNGUtils.toSuiteDescriptor(result.getMethod().getTestClass());
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
        suiteDescriptor, result.getThrowable());
  }

  @Override
  public void onConfigurationSkip(ITestResult result) {
    // ignore
  }

  @Override
  public void onTestStart(final ITestResult result) {
    TestSuiteDescriptor suiteDescriptor =
        TestNGUtils.toSuiteDescriptor(result.getMethod().getTestClass());
    String testSuiteName = result.getInstanceName();
    String testName =
        (result.getName() != null) ? result.getName() : result.getMethod().getMethodName();
    String testParameters = TestNGUtils.getParameters(result);
    List<String> groups = TestNGUtils.getGroups(result);
    Class<?> testClass = TestNGUtils.getTestClass(result);
    Method testMethod = TestNGUtils.getTestMethod(result);
    String testMethodName = testMethod != null ? testMethod.getName() : null;
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        suiteDescriptor,
        result,
        testSuiteName,
        testName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        testParameters,
        groups,
        testClass,
        testMethodName,
        testMethod,
        isRetry(result));
  }

  private boolean isRetry(final ITestResult result) {
    IRetryAnalyzer retryAnalyzer = TestNGUtils.getRetryAnalyzer(result);
    if (retryAnalyzer instanceof RetryAnalyzer) {
      RetryAnalyzer datadogAnalyzer = (RetryAnalyzer) retryAnalyzer;
      return datadogAnalyzer.currentExecutionIsRetry();
    }
    return false;
  }

  @Override
  public void onTestSuccess(final ITestResult result) {
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(result);
  }

  @Override
  public void onTestFailure(final ITestResult result) {
    Throwable throwable = result.getThrowable();
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(result, throwable);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(result);
  }

  @Override
  public void onTestFailedButWithinSuccessPercentage(final ITestResult result) {
    onTestFailure(result);
  }

  @Override
  public void onTestSkipped(final ITestResult result) {
    Throwable throwable = result.getThrowable();
    if (TestNGUtils.wasRetried(result)) {
      // TestNG reports tests retried with IRetryAnalyzer as skipped,
      // this is done to avoid failing the build when retrying tests.
      // We want to report such tests as failed to Datadog,
      // to provide more accurate data (and to enable flakiness detection)
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(result, throwable);
    } else {
      // Typically the way of skipping a TestNG test is throwing a SkipException
      String reason = throwable != null ? throwable.getMessage() : null;
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(result, reason);
    }
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(result);
  }
}
