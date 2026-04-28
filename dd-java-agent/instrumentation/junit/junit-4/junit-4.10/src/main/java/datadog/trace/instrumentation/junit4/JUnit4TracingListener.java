package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionTracker;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Ignore;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

public class JUnit4TracingListener extends TracingListener {

  private static final String FRAMEWORK_NAME = "junit4";
  private static final String FRAMEWORK_VERSION = JUnit4Utils.getVersion();

  private final ContextStore<Description, TestExecutionTracker> executionTrackers;

  /**
   * Suites for which {@code onTestSuiteStart} has been fired (from either the normal
   * ParentRunner-based flow or via lazy-registration in {@link #testStarted}). Used to keep
   * lifecycle events idempotent and to know which auto-started suite still needs closing.
   */
  private final Set<TestSuiteDescriptor> startedSuites = ConcurrentHashMap.newKeySet();

  /**
   * Last suite lazy-started from {@link #testStarted} because no {@link #testSuiteStarted} event
   * was observed for it first. This has been seen under {@code
   * com.google.testing.junit.runner.BazelTestRunner}, where the suite-start advice in {@code
   * JUnit4SuiteEventsInstrumentation} does not fire for reasons still to be pinpointed (likely a
   * classloader or runner-wrapping quirk specific to the Bazel test launcher). Closed when the next
   * test belongs to a different suite, or when the whole test run finishes.
   *
   * <p>TODO: investigate the exact cause under {@code BazelTestRunner} and add a dedicated
   * instrumentation that emits proper suite-lifecycle events instead of relying on this fallback.
   */
  private volatile TestSuiteDescriptor autoStartedSuite;

  public JUnit4TracingListener(ContextStore<Description, TestExecutionTracker> executionTrackers) {
    this.executionTrackers = executionTrackers;
  }

  public void testSuiteStarted(final Description description) {
    if (!JUnit4Utils.isSuiteContainingChildren(description)) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    TestSuiteDescriptor suiteDescriptor = JUnit4Utils.toSuiteDescriptor(description);
    if (!startedSuites.add(suiteDescriptor)) {
      return; // already started (idempotent vs. lazy-registration or duplicate events)
    }
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
    if (!startedSuites.remove(suiteDescriptor)) {
      return; // never started
    }
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

    lazyStartSuiteIfNeeded(suiteDescriptor, description, testSourceData);

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
            executionTrackers.get(description));
  }

  @Override
  public void testRunFinished(Result result) {
    closeAutoStartedSuite();
  }

  private void lazyStartSuiteIfNeeded(
      TestSuiteDescriptor newSuite, Description description, TestSourceData testSourceData) {
    if (startedSuites.contains(newSuite)) {
      return;
    }
    closeAutoStartedSuite();

    Class<?> testClass = testSourceData.getTestClass();
    String testSuiteName = JUnit4Utils.getSuiteName(testClass, description);
    List<String> categories = JUnit4Utils.getCategories(testClass, null);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.JUNIT4)
        .onTestSuiteStart(
            newSuite,
            testSuiteName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            testClass,
            categories,
            false,
            TestFrameworkInstrumentation.JUNIT4,
            null);
    startedSuites.add(newSuite);
    autoStartedSuite = newSuite;
  }

  private void closeAutoStartedSuite() {
    TestSuiteDescriptor suite = autoStartedSuite;
    if (suite == null) {
      return;
    }
    autoStartedSuite = null;
    if (startedSuites.remove(suite)) {
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.JUNIT4)
          .onTestSuiteFinish(suite, null);
    }
  }

  @Override
  public void testFinished(final Description description) {
    if (JUnit4Utils.isJUnitPlatformRunnerTest(description)) {
      return;
    }

    TestDescriptor testDescriptor = JUnit4Utils.toTestDescriptor(description);
    TestExecutionTracker executionTracker = executionTrackers.get(description);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.JUNIT4)
        .onTestFinish(testDescriptor, null, executionTracker);
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
            executionTrackers.get(description));
  }
}
