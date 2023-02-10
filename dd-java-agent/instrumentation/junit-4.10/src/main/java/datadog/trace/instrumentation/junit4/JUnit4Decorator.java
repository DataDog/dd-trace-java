package datadog.trace.instrumentation.junit4;

import datadog.trace.api.DisableTestTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import java.lang.reflect.Method;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

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
    return description.getAnnotation(DisableTestTrace.class) != null
        || (description.getTestClass() != null
            && description.getTestClass().getAnnotation(DisableTestTrace.class) != null);
  }

  public void onTestStart(
      final AgentSpan span, final String version, final Description description) {
    Class<?> testClass = description.getTestClass();
    Method testMethod = JUnit4Utils.getTestMethod(description);

    String testSuiteName = description.getClassName();
    String testName = JUnit4Utils.getTestName(description, testMethod);
    String testParameters = JUnit4Utils.getParameters(description);

    afterTestStart(span, testSuiteName, testName, testParameters, version, testClass, testMethod);

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
    final Class<?> testClass = description.getTestClass();

    final String testSuiteName = description.getClassName();
    final String testName = JUnit4Utils.getTestName(description, testMethod);

    afterTestStart(span, testSuiteName, testName, null, version, testClass, testMethod);

    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    span.setTag(Tags.TEST_SKIP_REASON, reason);

    beforeFinish(span);
  }

  public void onTestFailure(final AgentSpan span, final Failure failure) {
    final Throwable throwable = failure.getException();
    if (throwable != null) {
      span.setError(true);
      span.addThrowable(throwable);
      span.setTag(Tags.TEST_STATUS, TEST_FAIL);
    }
  }

  public void onTestAssumptionFailure(final AgentSpan span, final Failure failure) {
    // The consensus is to treat "assumptions failure" as skipped tests.
    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    final Throwable throwable = failure.getException();
    if (throwable != null) {
      span.setTag(Tags.TEST_SKIP_REASON, throwable.getMessage());
    }
  }

  public void onTestFinish(final AgentSpan span) {
    beforeFinish(span);
  }
}
