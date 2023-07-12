package datadog.trace.instrumentation.testng;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.testng.IConfigurationListener;
import org.testng.IExecutionListener;
import org.testng.ITestClass;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

public class TracingListener extends TestNGClassListener
    implements ITestListener, IExecutionListener, IConfigurationListener {

  private final TestEventsHandler testEventsHandler;

  public TracingListener(final String version) {
    Path currentPath = Paths.get("").toAbsolutePath();
    testEventsHandler =
        InstrumentationBridge.createTestEventsHandler("testng", "testng", version, currentPath);
  }

  @Override
  public void onStart(final ITestContext context) {
    // ignore
  }

  @Override
  public void onFinish(final ITestContext context) {
    // ignore
  }

  @Override
  public void onExecutionStart() {
    testEventsHandler.onTestModuleStart();
  }

  @Override
  public void onExecutionFinish() {
    testEventsHandler.onTestModuleFinish(ItrFilter.INSTANCE.testsSkipped());
  }

  @Override
  protected void onBeforeClass(ITestClass testClass, boolean parallelized) {
    String testSuiteName = testClass.getName();
    Class<?> testSuiteClass = testClass.getRealClass();
    List<String> groups = TestNGUtils.getGroups(testClass);
    testEventsHandler.onTestSuiteStart(
        testSuiteName, null, null, testSuiteClass, groups, parallelized);
  }

  @Override
  protected void onAfterClass(ITestClass testClass) {
    String testSuiteName = testClass.getName();
    Class<?> testSuiteClass = testClass.getRealClass();
    testEventsHandler.onTestSuiteFinish(testSuiteName, testSuiteClass);
  }

  @Override
  public void onConfigurationSuccess(ITestResult result) {
    // ignore
  }

  @Override
  public void onConfigurationFailure(ITestResult result) {
    // suite setup or suite teardown failed
    String testSuiteName = result.getInstanceName();
    Class<?> testClass = TestNGUtils.getTestClass(result);
    testEventsHandler.onTestSuiteFailure(testSuiteName, testClass, result.getThrowable());
  }

  @Override
  public void onConfigurationSkip(ITestResult result) {
    // ignore
  }

  @Override
  public void onTestStart(final ITestResult result) {
    String testSuiteName = result.getInstanceName();
    String testName =
        (result.getTestName() != null) ? result.getTestName() : result.getMethod().getMethodName();
    String testQualifier = result.getTestContext().getName();
    String testParameters = TestNGUtils.getParameters(result);
    List<String> groups = TestNGUtils.getGroups(result);

    Class<?> testClass = TestNGUtils.getTestClass(result);
    Method testMethod = TestNGUtils.getTestMethod(result);
    String testMethodName = testMethod != null ? testMethod.getName() : null;

    testEventsHandler.onTestStart(
        testSuiteName,
        testName,
        testQualifier,
        null,
        null,
        testParameters,
        groups,
        testClass,
        testMethodName,
        testMethod);
  }

  @Override
  public void onTestSuccess(final ITestResult result) {
    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    String testName =
        (result.getTestName() != null) ? result.getTestName() : result.getMethod().getMethodName();
    String testQualifier = result.getTestContext().getName();
    String testParameters = TestNGUtils.getParameters(result);
    testEventsHandler.onTestFinish(
        testSuiteName, testClass, testName, testQualifier, testParameters);
  }

  @Override
  public void onTestFailure(final ITestResult result) {
    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    String testName =
        (result.getTestName() != null) ? result.getTestName() : result.getMethod().getMethodName();
    String testQualifier = result.getTestContext().getName();
    String testParameters = TestNGUtils.getParameters(result);

    final Throwable throwable = result.getThrowable();
    testEventsHandler.onTestFailure(
        testSuiteName, testClass, testName, testQualifier, testParameters, throwable);
    testEventsHandler.onTestFinish(
        testSuiteName, testClass, testName, testQualifier, testParameters);
  }

  @Override
  public void onTestFailedButWithinSuccessPercentage(final ITestResult result) {
    onTestFailure(result);
  }

  @Override
  public void onTestSkipped(final ITestResult result) {
    final String testSuiteName = result.getInstanceName();
    final Class<?> testClass = TestNGUtils.getTestClass(result);
    String testName =
        (result.getTestName() != null) ? result.getTestName() : result.getMethod().getMethodName();
    String testQualifier = result.getTestContext().getName();
    String testParameters = TestNGUtils.getParameters(result);

    // Typically the way of skipping a TestNG test is throwing a SkipException
    Throwable throwable = result.getThrowable();
    String reason = throwable != null ? throwable.getMessage() : null;
    testEventsHandler.onTestSkip(
        testSuiteName, testClass, testName, testQualifier, testParameters, reason);
    testEventsHandler.onTestFinish(
        testSuiteName, testClass, testName, testQualifier, testParameters);
  }
}
