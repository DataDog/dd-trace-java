package datadog.trace.instrumentation.junit4;

import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.util.Strings;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import munit.Suite;
import munit.Tag;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

public class MUnitTracingListener extends TracingListener {

  public static final String FRAMEWORK_NAME = "munit";
  public static final String FRAMEWORK_VERSION = getVersion();
  private final ContextStore<Description, TestRetryPolicy> retryPolicies;

  public MUnitTracingListener(ContextStore<Description, TestRetryPolicy> retryPolicies) {
    this.retryPolicies = retryPolicies;
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
    List<String> categories = getCategories(description);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteStart(
        suiteDescriptor,
        testSuiteName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        testClass,
        categories,
        false,
        TestFrameworkInstrumentation.MUNIT);
  }

  public void testSuiteFinished(final Description description) {
    if (!isSuiteContainingChildren(description)) {
      // Not all test suites contain tests.
      // Given that test suites can be nested in other test suites,
      // we are only interested in the innermost ones where the actual test cases reside
      return;
    }

    TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFinish(suiteDescriptor);
  }

  @Override
  public void testStarted(final Description description) {
    TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
    TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
    String testSuiteName = description.getClassName();
    Class<?> testClass = description.getTestClass();
    String testName = description.getMethodName();
    List<String> categories = getCategories(description);
    TestRetryPolicy retryPolicy = retryPolicies.get(description);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
        suiteDescriptor,
        testDescriptor,
        testSuiteName,
        testName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        null,
        categories,
        testClass,
        null,
        null,
        retryPolicy != null && retryPolicy.currentExecutionIsRetry());
  }

  @Override
  public void testFinished(final Description description) {
    TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(testDescriptor);
  }

  // same callback is executed both for test cases and test suites (for setup/teardown errors)
  @Override
  public void testFailure(final Failure failure) {
    Throwable throwable = failure.getException();
    Description description = failure.getDescription();

    String testName = description.getMethodName();
    if (Strings.isNotBlank(testName)) {
      TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFailure(testDescriptor, throwable);
    } else {
      TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteFailure(suiteDescriptor, throwable);
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
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(testDescriptor, reason);

    } else if (testClass != null) {
      TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(suiteDescriptor, reason);
      for (Description child : description.getChildren()) {
        testCaseIgnored(child);
      }
    }
  }

  @Override
  public void testIgnored(final Description description) {
    Class<?> testClass = description.getTestClass();
    String testSuiteName = description.getClassName();
    String testName = description.getMethodName();

    if (Strings.isNotBlank(testName)) {
      TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
      if (!isTestInProgress()) {
        // earlier versions of MUnit (e.g. 0.7.28) trigger "testStarted" event for ignored tests,
        // while newer versions don't
        TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
        List<String> categories = getCategories(description);
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestStart(
            suiteDescriptor,
            testDescriptor,
            testSuiteName,
            testName,
            FRAMEWORK_NAME,
            FRAMEWORK_VERSION,
            null,
            categories,
            testClass,
            null,
            null,
            false);
      }
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSkip(testDescriptor, null);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestFinish(testDescriptor);

    } else if (testClass != null) {
      TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
      TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestSuiteSkip(suiteDescriptor, null);
      for (Description child : description.getChildren()) {
        testCaseIgnored(child);
      }
    }
  }

  private boolean isTestInProgress() {
    final AgentScope scope = AgentTracer.activeScope();
    if (scope == null) {
      return false;
    }
    AgentSpan scopeSpan = scope.span();
    String spanType = scopeSpan.getSpanType();
    return spanType != null && spanType.contentEquals(InternalSpanTypes.TEST);
  }

  private void testCaseIgnored(final Description description) {
    TestSuiteDescriptor suiteDescriptor = MUnitUtils.toSuiteDescriptor(description);
    TestDescriptor testDescriptor = MUnitUtils.toTestDescriptor(description);
    String testSuiteName = description.getClassName();
    String testName = description.getMethodName();
    Class<?> testClass = description.getTestClass();
    List<String> categories = getCategories(description);
    TestEventsHandlerHolder.TEST_EVENTS_HANDLER.onTestIgnore(
        suiteDescriptor,
        testDescriptor,
        testSuiteName,
        testName,
        FRAMEWORK_NAME,
        FRAMEWORK_VERSION,
        null,
        categories,
        testClass,
        null,
        null,
        null);
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

  private static List<String> getCategories(Description description) {
    List<String> categories = new ArrayList<>();
    for (Annotation annotation : description.getAnnotations()) {
      if (annotation.annotationType() == Tag.class) {
        Tag tag = (Tag) annotation;
        categories.add(tag.value());
      }
    }
    return categories;
  }
}
