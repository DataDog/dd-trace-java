package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Ignore;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

public class JUnit4TracingListener extends TracingListener {

  private static final String FRAMEWORK_NAME = "junit4";
  private static final String FRAMEWORK_VERSION = JUnit4Utils.getVersion();

  private final ContextStore<Description, TestExecutionHistory> executionHistories;

  public JUnit4TracingListener(ContextStore<Description, TestExecutionHistory> executionHistories) {
    this.executionHistories = executionHistories;
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
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.JUNIT4)
        .onTestSuiteStart(
            suiteDescriptor,
            testSuiteName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            testClass,
            categories,
            false,
            TestFrameworkInstrumentation.JUNIT4,
            null);
  }

  public void testSuiteFinished(final Description description) {
    if (!JUnit4Utils.isSuiteContainingChildren(description)) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.JUNIT4)
        .onTestSuiteFinish(suiteDescriptor, null);
  }

  @Override
  public void testStarted(final Description description) {
    if (JUnit4Utils.isJUnitPlatformRunnerTest(description)) {
      return;
    }

    TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
    TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);
    TestSourceData testSourceData = JUnit4Utils.toTestSourceData(description);

    String testName = JUnit4Utils.getTestName(description, testSourceData.getTestMethod());
    String testParameters = JUnit4Utils.getParameters(description);
    List<String> categories =
        JUnit4Utils.getCategories(testSourceData.getTestClass(), testSourceData.getTestMethod());

    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.JUNIT4)
        .onTestStart(
            suiteDescriptor,
            testDescriptor,
            testName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            testParameters,
            categories,
            testSourceData,
            null,
            executionHistories.get(description));
  }

  @Override
  public void testFinished(final Description description) {
    if (JUnit4Utils.isJUnitPlatformRunnerTest(description)) {
      return;
    }

    TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);
    TestExecutionHistory executionHistory = executionHistories.get(description);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.JUNIT4)
        .onTestFinish(testDescriptor, null, executionHistory);
  }

  // same callback is executed both for test cases and test suites (for setup/teardown errors)
  @Override
  public void testFailure(final Failure failure) {
    Description description = failure.getDescription();
    if (JUnit4Utils.isJUnitPlatformRunnerTest(description)) {
      return;
    }

    if (JUnit4Utils.isTestSuiteDescription(description)) {
      TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.JUNIT4)
          .onTestSuiteFailure(suiteDescriptor, throwable);
    } else {
      TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);
      Throwable throwable = failure.getException();
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.JUNIT4)
          .onTestFailure(testDescriptor, throwable);
    }
  }

  @Override
  public void testAssumptionFailure(final Failure failure) {
    Description description = failure.getDescription();
    if (JUnit4Utils.isJUnitPlatformRunnerTest(description)) {
      return;
    }

    String reason;
    Throwable throwable = failure.getException();
    if (throwable != null) {
      reason = throwable.getMessage();
    } else {
      reason = null;
    }

    if (JUnit4Utils.isTestSuiteDescription(description)) {
      TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.JUNIT4)
          .onTestSuiteSkip(suiteDescriptor, reason);

      List<Method> testMethods = JUnit4Utils.getTestMethods(description.getTestClass());
      for (Method testMethod : testMethods) {
        testIgnored(description, testMethod, reason);
      }
    } else {
      TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.JUNIT4)
          .onTestSkip(testDescriptor, reason);
    }
  }

  @Override
  public void testIgnored(final Description description) {
    if (JUnit4Utils.isJUnitPlatformRunnerTest(description)) {
      return;
    }

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

      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.JUNIT4)
          .onTestSuiteStart(
              suiteDescriptor,
              testSuiteName,
              FRAMEWORK_NAME,
              FRAMEWORK_VERSION,
              testClass,
              categories,
              false,
              TestFrameworkInstrumentation.JUNIT4,
              null);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.JUNIT4)
          .onTestSuiteSkip(suiteDescriptor, reason);

      List<Method> testMethods = JUnit4Utils.getTestMethods(testClass);
      for (Method testMethod : testMethods) {
        testIgnored(description, testMethod, reason);
      }

      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.JUNIT4)
          .onTestSuiteFinish(suiteDescriptor, null);
    }
  }

  private void testIgnored(Description description, Method testMethod, String reason) {
    if (JUnit4Utils.isJUnitPlatformRunnerTest(description)) {
      return;
    }

    TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
    TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);

    Class<?> testClass = description.getTestClass();
    String testMethodName = testMethod != null ? testMethod.getName() : null;
    TestSourceData testSourceData = new TestSourceData(testClass, testMethod, testMethodName);

    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.JUNIT4)
        .onTestIgnore(
            suiteDescriptor,
            testDescriptor,
            testName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            testParameters,
            categories,
            testSourceData,
            reason,
            executionHistories.get(description));
  }
}
