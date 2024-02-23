package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import java.lang.reflect.Method;
import java.util.List;
import junit.runner.Version;
import org.junit.Ignore;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

public class JUnit4TracingListener extends TracingListener {

  private static final String FRAMEWORK_NAME = "junit4";
  private static final String FRAMEWORK_VERSION = Version.id();

  private final ContextStore<Description, TestRetryPolicy> retryPolicies;

  public JUnit4TracingListener(ContextStore<Description, TestRetryPolicy> retryPolicies) {
    this.retryPolicies = retryPolicies;
  }

  public void testSuiteStarted(final Description description) {
    if (!JUnit4Utils.isSuiteContainingChildren(description)) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
    Class<?> testClass = description.getTestClass();
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    List<String> categories = JUnit4Utils.getCategories(testClass, null);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        suiteDescriptor,
        testSuiteName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        testClass,
        categories,
        false,
        TestFrameworkInstrumentation.JUNIT4);
  }

  public void testSuiteFinished(final Description description) {
    if (!JUnit4Utils.isSuiteContainingChildren(description)) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(suiteDescriptor);
  }

  @Override
  public void testStarted(final Description description) {
    TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
    TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);
    Class<?> testClass = description.getTestClass();
    Method testMethod = JUnit4Utils.getTestMethod(description);
    String testMethodName = testMethod != null ? testMethod.getName() : null;
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);
    TestRetryPolicy retryPolicy = retryPolicies.get(description);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        suiteDescriptor,
        testDescriptor,
        testSuiteName,
        testName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod,
        retryPolicy != null && retryPolicy.currentExecutionIsRetry());
  }

  @Override
  public void testFinished(final Description description) {
    TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(testDescriptor);
  }

  // same callback is executed both for test cases and test suites (for setup/teardown errors)
  @Override
  public void testFailure(final Failure failure) {
    Description description = failure.getDescription();
    if (JUnit4Utils.isTestSuiteDescription(description)) {
      TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(suiteDescriptor, throwable);
    } else {
      TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(testDescriptor, throwable);
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
    if (JUnit4Utils.isTestSuiteDescription(description)) {
      TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(suiteDescriptor, reason);

      List<Method> testMethods = JUnit4Utils.getTestMethods(description.getTestClass());
      for (Method testMethod : testMethods) {
        testIgnored(description, testMethod, reason);
      }
    } else {
      TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(testDescriptor, reason);
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

      TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
      Class<?> testClass = description.getTestClass();
      String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
      List<String> categories = JUnit4Utils.getCategories(testClass, null);

      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
          suiteDescriptor,
          testSuiteName,
          FRAMEWORK_NAME,
          FRAMEWORK_VERSION,
          testClass,
          categories,
          false,
          TestFrameworkInstrumentation.JUNIT4);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(suiteDescriptor, reason);

      List<Method> testMethods = JUnit4Utils.getTestMethods(testClass);
      for (Method testMethod : testMethods) {
        testIgnored(description, testMethod, reason);
      }

      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(suiteDescriptor);
    }
  }

  private void testIgnored(Description description, Method testMethod, String reason) {
    TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
    TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);
    Class<?> testClass = description.getTestClass();
    String testMethodName = testMethod != null ? testMethod.getName() : null;
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
        suiteDescriptor,
        testDescriptor,
        testSuiteName,
        testName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod,
        reason);
  }
}
