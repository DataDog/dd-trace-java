package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.execution.TestExecutionHistory;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.Strings;
import java.util.List;
import munit.Suite;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

public class MUnitTracingListener extends TracingListener {

  public static final String FRAMEWORK_NAME = "munit";
  public static final String FRAMEWORK_VERSION = getVersion();
  private final ContextStore<Description, TestExecutionHistory> executionHistories;

  public MUnitTracingListener(ContextStore<Description, TestExecutionHistory> executionHistories) {
    this.executionHistories = executionHistories;
  }

  public static String getVersion() {
    Package munitPackage = Suite.class.getPackage();
    return munitPackage.getImplementationVersion();
  }

  public void testSuiteStarted(final Description description) {
    if (!isSuiteContainingChildren(description)) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
    Class<?> testClass = description.getTestClass();
    String testSuiteName = description.getClassName();
    List<String> categories = MUnitUtils.getCategories(description);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.MUNIT)
        .onTestSuiteStart(
            suiteDescriptor,
            testSuiteName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            testClass,
            categories,
            false,
            TestFrameworkInstrumentation.MUNIT,
            null);
  }

  public void testSuiteFinished(final Description description) {
    if (!isSuiteContainingChildren(description)) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.MUNIT)
        .onTestSuiteFinish(suiteDescriptor, null);
  }

  @Override
  public void testStarted(final Description description) {
    TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
    TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
    String testName = description.getMethodName();
    List<String> categories = MUnitUtils.getCategories(description);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.MUNIT)
        .onTestStart(
            suiteDescriptor,
            testDescriptor,
            testName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            null,
            categories,
            JUnit4Utils.toTestSourceData(description),
            null,
            executionHistories.get(description));
  }

  @Override
  public void testFinished(final Description description) {
    TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
    TestExecutionHistory executionHistory = executionHistories.get(description);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.MUNIT)
        .onTestFinish(testDescriptor, null, executionHistory);
  }

  // same callback is executed both for test cases and test suites (for setup/teardown errors)
  @Override
  public void testFailure(final Failure failure) {
    Throwable throwable = failure.getException();
    Description description = failure.getDescription();

    String testName = description.getMethodName();
    if (Strings.isNotBlank(testName)) {
      TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.MUNIT)
          .onTestFailure(testDescriptor, throwable);
    } else {
      TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.MUNIT)
          .onTestSuiteFailure(suiteDescriptor, throwable);
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
    String testName = description.getMethodName();

    if (Strings.isNotBlank(testName)) {
      TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.MUNIT)
          .onTestSkip(testDescriptor, reason);

    } else if (testClass != null) {
      TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.MUNIT)
          .onTestSuiteSkip(suiteDescriptor, reason);
      for (Description child : description.getChildren()) {
        testCaseIgnored(child);
      }
    }
  }

  @Override
  public void testIgnored(final Description description) {
    Class<?> testClass = description.getTestClass();
    String testName = description.getMethodName();

    if (Strings.isNotBlank(testName)) {
      TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
      if (!isSpanInProgress(InternalSpanTypes.TEST)) {
        // earlier versions of MUnit (e.g. 0.7.28) trigger "testStarted" event for ignored tests,
        // while newer versions don't
        TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
        List<String> categories = MUnitUtils.getCategories(description);
        TestEventsHandlerHolder.HANDLERS
            .get(TestFrameworkInstrumentation.MUNIT)
            .onTestStart(
                suiteDescriptor,
                testDescriptor,
                testName,
                FRAMEWORK_NAME,
                FRAMEWORK_VERSION,
                null,
                categories,
                JUnit4Utils.toTestSourceData(description),
                null,
                null);
      }
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.MUNIT)
          .onTestSkip(testDescriptor, null);
      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.MUNIT)
          .onTestFinish(testDescriptor, null, executionHistories.get(description));

    } else if (testClass != null) {
      TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);

      boolean suiteStarted = isSpanInProgress(InternalSpanTypes.TEST_SUITE_END);
      if (!suiteStarted) {
        // there is a bug in MUnit 1.0.1+: start/finish events are not fired for skipped suites
        String testSuiteName = description.getClassName();
        List<String> categories = MUnitUtils.getCategories(description);
        TestEventsHandlerHolder.HANDLERS
            .get(TestFrameworkInstrumentation.MUNIT)
            .onTestSuiteStart(
                suiteDescriptor,
                testSuiteName,
                FRAMEWORK_NAME,
                FRAMEWORK_VERSION,
                testClass,
                categories,
                false,
                TestFrameworkInstrumentation.MUNIT,
                null);
      }

      TestEventsHandlerHolder.HANDLERS
          .get(TestFrameworkInstrumentation.MUNIT)
          .onTestSuiteSkip(suiteDescriptor, null);
      for (Description child : description.getChildren()) {
        testCaseIgnored(child);
      }

      if (!suiteStarted) {
        TestEventsHandlerHolder.HANDLERS
            .get(TestFrameworkInstrumentation.MUNIT)
            .onTestSuiteFinish(suiteDescriptor, null);
      }
    }
  }

  private static boolean isSpanInProgress(UTF8BytesString type) {
    final AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return false;
    }
    String spanType = span.getSpanType();
    return spanType != null && spanType.contentEquals(type);
  }

  private void testCaseIgnored(final Description description) {
    TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
    TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
    String testName = description.getMethodName();
    List<String> categories = MUnitUtils.getCategories(description);
    TestEventsHandlerHolder.HANDLERS
        .get(TestFrameworkInstrumentation.MUNIT)
        .onTestIgnore(
            suiteDescriptor,
            testDescriptor,
            testName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            null,
            categories,
            JUnit4Utils.toTestSourceData(description),
            null,
            executionHistories.get(description));
  }

  private static boolean isSuiteContainingChildren(final Description description) {
    Class<?> testClass = description.getTestClass();
    if (testClass == null) {
      return false;
    }
    for (Description child : description.getChildren()) {
      if (Strings.isNotBlank(child.getMethodName())) {
        return true;
      }
    }
    return false;
  }
}
