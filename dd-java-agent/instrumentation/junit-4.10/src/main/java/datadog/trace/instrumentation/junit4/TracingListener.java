package datadog.trace.instrumentation.junit4;

import java.lang.reflect.Method;
import java.util.List;
import junit.runner.Version;
import org.junit.Ignore;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runners.model.TestClass;

public class TracingListener extends RunListener {

  private final String version;

  public TracingListener() {
    version = Version.id();
  }

  @Override
  public void testRunStarted(Description description) {
    // on op
  }

  @Override
  public void testRunFinished(Result result) {
    // on op
  }

  public void testSuiteStarted(final TestClass junitTestClass, final Description description) {
    if (!JUnit4Utils.isSuiteContainingChildren(junitTestClass, description)) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    Class<?> testClass = junitTestClass.getJavaClass();
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);

    String testFramework = JUnit4Utils.getTestFramework(description);
    String testFrameworkVersion =
        !JUnit4Utils.CUCUMBER_FRAMEWORK.equals(testFramework)
            ? version
            : JUnit4Utils.getCucumberVersion(description);

    List<String> categories = JUnit4Utils.getCategories(testClass, null);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        testSuiteName, testFramework, testFrameworkVersion, testClass, categories, false);
  }

  public void testSuiteFinished(final TestClass junitTestClass, final Description description) {
    if (!JUnit4Utils.isSuiteContainingChildren(junitTestClass, description)) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    Class<?> testClass = junitTestClass.getJavaClass();
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, testClass);
  }

  @Override
  public void testStarted(final Description description) {
    Class<?> testClass = description.getTestClass();
    Method testMethod = JUnit4Utils.getTestMethod(description);
    String testMethodName = testMethod != null ? testMethod.getName() : null;

    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    String testName = JUnit4Utils.getTestName(description, testMethod);

    String testFramework = JUnit4Utils.getTestFramework(description);
    String testFrameworkVersion =
        !JUnit4Utils.CUCUMBER_FRAMEWORK.equals(testFramework)
            ? version
            : JUnit4Utils.getCucumberVersion(description);

    String testParameters = JUnit4Utils.getParameters(description);
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        testSuiteName,
        testName,
        null,
        testFramework,
        testFrameworkVersion,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod);
  }

  @Override
  public void testFinished(final Description description) {
    Class<?> testClass = description.getTestClass();
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    Method testMethod = JUnit4Utils.getTestMethod(description);
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(
        testSuiteName, testClass, testName, null, testParameters);
  }

  // same callback is executed both for test cases and test suites (for setup/teardown errors)
  @Override
  public void testFailure(final Failure failure) {
    Description description = failure.getDescription();
    Class<?> testClass = description.getTestClass();
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    if (JUnit4Utils.isTestSuiteDescription(description)) {
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(
          testSuiteName, testClass, throwable);
    } else {
      Method testMethod = JUnit4Utils.getTestMethod(description);
      String testName = JUnit4Utils.getTestName(description, testMethod);
      String testParameters = JUnit4Utils.getParameters(description);
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(
          testSuiteName, testClass, testName, null, testParameters, throwable);
    }
  }

  @Override
  public void testAssumptionFailure(final Failure failure) {
    String reason;
    Throwable throwable = failure.getException();
    if (throwable != null) {
      reason = throwable.getMessage();
    } else {
      reason = null;
    }

    Description description = failure.getDescription();
    Class<?> testClass = description.getTestClass();
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);

    if (JUnit4Utils.isTestSuiteDescription(description)) {
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, testClass, reason);

      List<Method> testMethods = JUnit4Utils.getTestMethods(description.getTestClass());
      for (Method testMethod : testMethods) {
        testIgnored(description, testMethod, reason);
      }
    } else {
      Method testMethod = JUnit4Utils.getTestMethod(description);
      String testName = JUnit4Utils.getTestName(description, testMethod);
      String testParameters = JUnit4Utils.getParameters(description);

      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(
          testSuiteName, testClass, testName, null, testParameters, reason);
    }
  }

  @Override
  public void testIgnored(final Description description) {
    final Ignore ignore = description.getAnnotation(Ignore.class);
    final String reason = ignore != null ? ignore.value() : null;

    if (JUnit4Utils.isTestCaseDescription(description)) {
      Method testMethod = JUnit4Utils.getTestMethod(description);
      testIgnored(description, testMethod, reason);

    } else if (JUnit4Utils.isTestSuiteDescription(description)) {

      Class<?> testClass = description.getTestClass();
      String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);

      String testFramework = JUnit4Utils.getTestFramework(description);
      String testFrameworkVersion =
          !JUnit4Utils.CUCUMBER_FRAMEWORK.equals(testFramework)
              ? version
              : JUnit4Utils.getCucumberVersion(description);

      List<String> categories = JUnit4Utils.getCategories(testClass, null);

      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
          testSuiteName, testFramework, testFrameworkVersion, testClass, categories, false);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(testSuiteName, testClass, reason);

      List<Method> testMethods = JUnit4Utils.getTestMethods(testClass);
      for (Method testMethod : testMethods) {
        testIgnored(description, testMethod, reason);
      }

      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(testSuiteName, testClass);
    }
  }

  private void testIgnored(Description description, Method testMethod, String reason) {
    Class<?> testClass = description.getTestClass();
    String testMethodName = testMethod != null ? testMethod.getName() : null;

    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    String testName = JUnit4Utils.getTestName(description, testMethod);

    String testFramework = JUnit4Utils.getTestFramework(description);
    String testFrameworkVersion =
        !JUnit4Utils.CUCUMBER_FRAMEWORK.equals(testFramework)
            ? version
            : JUnit4Utils.getCucumberVersion(description);

    String testParameters = JUnit4Utils.getParameters(description);
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);

    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
        testSuiteName,
        testName,
        null,
        testFramework,
        testFrameworkVersion,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod,
        reason);
  }
}
