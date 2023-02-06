package datadog.trace.instrumentation.junit4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.DisableTestTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runners.model.TestClass;

public class JUnit4Decorator extends TestDecorator {
  public static final JUnit4Decorator DECORATE = new JUnit4Decorator();

  @Override
  public String testFramework() {
    return "junit4";
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"junit", "junit-4", "junit-4-suite-events"};
  }

  @Override
  public String component() {
    return "junit";
  }

  public boolean skipTrace(final Description description) {
    if (description.getAnnotation(DisableTestTrace.class) != null) {
      return true;
    }
    Class<?> testClass = description.getTestClass();
    return testClass != null && testClass.getAnnotation(DisableTestTrace.class) != null;
  }

  public boolean skipTrace(final TestClass junitTestClass) {
    if (junitTestClass == null) {
      return false;
    }
    Class<?> testClass = junitTestClass.getJavaClass();
    return testClass != null && testClass.getAnnotation(DisableTestTrace.class) != null;
  }

  public void onTestSuiteStart(
      final AgentSpan span, final String version, final TestClass junitTestClass) {
    String testSuiteName = junitTestClass.getName();
    Class<?> testClass = junitTestClass.getJavaClass();
    List<String> categories = JUnit4Utils.getCategories(testClass, null);

    afterTestSuiteStart(span, testSuiteName, version, testClass, categories);
  }

  public void onTestSuiteIgnored(
      final AgentSpan span,
      final String version,
      final Description description,
      final String reason) {
    Class<?> testClass = description.getTestClass();
    String testSuiteName = testClass.getName();
    List<String> categories = JUnit4Utils.getCategories(testClass, null);

    afterTestSuiteStart(span, testSuiteName, version, testClass, categories);

    List<Method> testMethods = testMethods(testClass, Test.class);
    for (Method testMethod : testMethods) {
      final AgentSpan testCaseSpan = startSpan("junit.test");
      onTestIgnored(testCaseSpan, version, description, testMethod, reason);
      // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
      // tracer.
      testCaseSpan.finishWithDuration(1L);
    }

    span.setTag(Tags.TEST_SKIP_REASON, reason);

    beforeTestSuiteFinish(span, testSuiteName, testClass);
  }

  public void onTestSuiteFinish(final AgentSpan span, final TestClass junitTestClass) {
    String testSuiteName = junitTestClass.getName();
    Class<?> testClass = junitTestClass.getJavaClass();
    beforeTestSuiteFinish(span, testSuiteName, testClass);
  }

  public void onTestStart(
      final AgentSpan span, final String version, final Description description) {
    Class<?> testClass = description.getTestClass();
    Method testMethod = JUnit4Utils.getTestMethod(description);
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);

    String testSuiteName = description.getClassName();
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);

    afterTestStart(
        span, testSuiteName, testName, testParameters, version, testClass, testMethod, categories);

    // We cannot set TEST_PASS status in onTestFinish(...) method because that method
    // is executed always after onTestFailure. For that reason, TEST_PASS status is preset
    // in onTestStart.
    span.setTag(Tags.TEST_STATUS, TEST_PASS);
  }

  public void onTestIgnored(
      final AgentSpan span,
      final String version,
      final Description description,
      final Method testMethod,
      final String reason) {
    Class<?> testClass = description.getTestClass();
    List<String> categories = JUnit4Utils.getCategories(testClass, testMethod);

    String testSuiteName = description.getClassName();
    String testName = JUnit4Utils.getTestName(description, testMethod);

    afterTestStart(span, testSuiteName, testName, null, version, testClass, testMethod, categories);

    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    span.setTag(Tags.TEST_SKIP_REASON, reason);

    beforeTestFinish(span, testSuiteName, testClass);
  }

  public void onTestFailure(final AgentSpan span, final Failure failure) {
    final Throwable throwable = failure.getException();
    if (throwable != null) {
      span.setError(true);
      span.addThrowable(throwable);
      span.setTag(Tags.TEST_STATUS, TEST_FAIL);
    }
  }

  public void onTestFinish(final AgentSpan span, final Description description) {
    String testSuiteName = description.getClassName();
    Class<?> testClass = description.getTestClass();
    beforeTestFinish(span, testSuiteName, testClass);
  }

  public void onTestAssumptionFailure(final AgentSpan span, final String reason) {
    // The consensus is to treat "assumptions failure" as skipped tests.
    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    if (reason != null) {
      span.setTag(Tags.TEST_SKIP_REASON, reason);
    }
  }

  public void onTestSuiteAssumptionFailure(
      final AgentSpan span,
      final String version,
      final Description description,
      final String reason) {
    // The consensus is to treat "assumptions failure" as skipped tests.
    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    if (reason != null) {
      span.setTag(Tags.TEST_SKIP_REASON, reason);
    }

    List<Method> testMethods = testMethods(description.getTestClass(), Test.class);
    for (Method testMethod : testMethods) {
      final AgentSpan testCaseSpan = startSpan("junit.test");
      onTestIgnored(testCaseSpan, version, description, testMethod, reason);
      // We set a duration of 1 ns, because a span with duration==0 has a special treatment in the
      // tracer.
      testCaseSpan.finishWithDuration(1L);
    }
  }
}
