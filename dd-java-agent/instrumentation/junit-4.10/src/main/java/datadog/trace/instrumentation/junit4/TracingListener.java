package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import junit.runner.Version;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runners.model.TestClass;

public class TracingListener extends RunListener {

  private final TestEventsHandler testEventsHandler;

  public TracingListener() {
    String version = Version.id();
    Path currentPath = Paths.get("").toAbsolutePath();
    testEventsHandler =
        InstrumentationBridge.createTestEventsHandler("junit", "junit4", version, currentPath);
  }

  @Override
  public void testRunStarted(Description description) {
    testEventsHandler.onTestModuleStart();
  }

  @Override
  public void testRunFinished(Result result) {
    testEventsHandler.onTestModuleFinish();
  }

  public void testSuiteStarted(final TestClass junitTestClass) {
    boolean containsTestCases = !junitTestClass.getAnnotatedMethods(Test.class).isEmpty();
    if (!containsTestCases) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    String testSuiteName = junitTestClass.getName();
    Class<?> testClass = junitTestClass.getJavaClass();
    List<String> categories = JUnit4Utils.getCategories(testClass, null);
    testEventsHandler.onTestSuiteStart(testSuiteName, null, null, testClass, categories);
  }

  public void testSuiteFinished(final TestClass junitTestClass) {
    boolean containsTestCases = !junitTestClass.getAnnotatedMethods(Test.class).isEmpty();
    if (!containsTestCases) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    String testSuiteName = junitTestClass.getName();
    Class<?> testClass = junitTestClass.getJavaClass();
    testEventsHandler.onTestSuiteFinish(testSuiteName, testClass);
  }

  @Override
  public void testStarted(final Description description) {
    Class<?> testClass = description.getTestClass();
    Method testMethod = JUnit4Utils.getTestMethod(description);

    String testSuiteName = description.getClassName();
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);

    testEventsHandler.onTestStart(
        testSuiteName, testName, null, null, testParameters, categories, testClass, testMethod);
  }

  @Override
  public void testFinished(final Description description) {
    String testSuiteName = description.getClassName();
    Class<?> testClass = description.getTestClass();
    Method testMethod = JUnit4Utils.getTestMethod(description);
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    testEventsHandler.onTestFinish(testSuiteName, testClass, testName, testParameters);
  }

  // same callback is executed both for test cases and test suites (for setup/teardown errors)
  @Override
  public void testFailure(final Failure failure) {
    Description description = failure.getDescription();
    String testSuiteName = description.getClassName();
    Class<?> testClass = description.getTestClass();
    if (JUnit4Utils.isTestSuiteDescription(description)) {
      Throwable throwable = failure.getException();
      testEventsHandler.onTestSuiteFailure(testSuiteName, testClass, throwable);
    } else {
      Method testMethod = JUnit4Utils.getTestMethod(description);
      String testName = JUnit4Utils.getTestName(description, testMethod);
      String testParameters = JUnit4Utils.getParameters(description);
      Throwable throwable = failure.getException();
      testEventsHandler.onTestFailure(
          testSuiteName, testClass, testName, testParameters, throwable);
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
    String testSuiteName = description.getClassName();
    Class<?> testClass = description.getTestClass();

    if (JUnit4Utils.isTestSuiteDescription(description)) {
      testEventsHandler.onTestSuiteSkip(testSuiteName, testClass, reason);

      List<Method> testMethods = JUnit4Utils.getTestMethods(description.getTestClass());
      for (Method testMethod : testMethods) {
        testIgnored(description, testMethod, reason);
      }
    } else {
      Method testMethod = JUnit4Utils.getTestMethod(description);
      String testName = JUnit4Utils.getTestName(description, testMethod);
      String testParameters = JUnit4Utils.getParameters(description);

      testEventsHandler.onTestSkip(testSuiteName, testClass, testName, testParameters, reason);
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
      String testSuiteName = testClass.getName();

      List<String> categories = JUnit4Utils.getCategories(testClass, null);

      testEventsHandler.onTestSuiteStart(testSuiteName, null, null, testClass, categories);
      testEventsHandler.onTestSuiteSkip(testSuiteName, testClass, reason);

      List<Method> testMethods = JUnit4Utils.getTestMethods(testClass);
      for (Method testMethod : testMethods) {
        testIgnored(description, testMethod, reason);
      }

      testEventsHandler.onTestSuiteFinish(testSuiteName, testClass);
    }
  }

  private void testIgnored(Description description, Method testMethod, String reason) {
    Class<?> testClass = description.getTestClass();

    String testSuiteName = description.getClassName();
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);

    testEventsHandler.onTestIgnore(
        testSuiteName,
        testName,
        null,
        null,
        testParameters,
        categories,
        testClass,
        testMethod,
        reason);
  }
}
