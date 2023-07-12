package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.util.AgentThreadFactory;
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

  private static final String GRADLE_TEST_WORKER_ID_SYSTEM_PROP = "org.gradle.test.worker";

  private final TestEventsHandler testEventsHandler;

  public TracingListener() {
    String version = Version.id();
    Path currentPath = Paths.get("").toAbsolutePath();
    testEventsHandler =
        InstrumentationBridge.createTestEventsHandler("junit", "junit4", version, currentPath);

    boolean isRunByGradle = System.getProperty(GRADLE_TEST_WORKER_ID_SYSTEM_PROP) != null;
    if (isRunByGradle) {
      applyTestRunFinishedPatch();
    }
  }

  /**
   * Gradle uses its own custom JUnit 4 runner. The runner does not invoke
   * org.junit.runner.notification.RunListener#testRunFinished, so we apply a "patch" to invoke it
   * before the JVM is shutdown
   */
  private void applyTestRunFinishedPatch() {
    Thread shutdownHook =
        AgentThreadFactory.newAgentThread(
            AgentThreadFactory.AgentThread.CI_GRADLE_JUNIT_SHUTDOWN_HOOK,
            () -> testRunFinished(null),
            false);
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  @Override
  public void testRunStarted(Description description) {
    testEventsHandler.onTestModuleStart();
  }

  @Override
  public void testRunFinished(Result result) {
    testEventsHandler.onTestModuleFinish(ItrFilter.INSTANCE.testsSkipped());
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
    testEventsHandler.onTestSuiteStart(testSuiteName, null, null, testClass, categories, false);
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
    String testMethodName = testMethod != null ? testMethod.getName() : null;

    String testSuiteName = description.getClassName();
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);

    testEventsHandler.onTestStart(
        testSuiteName,
        testName,
        null,
        null,
        null,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod);
  }

  @Override
  public void testFinished(final Description description) {
    String testSuiteName = description.getClassName();
    Class<?> testClass = description.getTestClass();
    Method testMethod = JUnit4Utils.getTestMethod(description);
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    testEventsHandler.onTestFinish(testSuiteName, testClass, testName, null, testParameters);
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

      testEventsHandler.onTestSkip(
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
      String testSuiteName = testClass.getName();

      List<String> categories = JUnit4Utils.getCategories(testClass, null);

      testEventsHandler.onTestSuiteStart(testSuiteName, null, null, testClass, categories, false);
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
    String testMethodName = testMethod != null ? testMethod.getName() : null;

    String testSuiteName = description.getClassName();
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);

    testEventsHandler.onTestIgnore(
        testSuiteName,
        testName,
        null,
        null,
        null,
        testParameters,
        categories,
        testClass,
        testMethodName,
        testMethod,
        reason);
  }
}
