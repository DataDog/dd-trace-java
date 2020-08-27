package datadog.trace.instrumentation.junit4;

import datadog.trace.api.DDTags;
import datadog.trace.api.DisableTestTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import lombok.extern.slf4j.Slf4j;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

@Slf4j
public class JUnit4Decorator extends TestDecorator {
  public static final JUnit4Decorator DECORATE = new JUnit4Decorator();

  @Override
  public String testFramework() {
    return "junit4";
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"junit", "junit-4"};
  }

  @Override
  public String component() {
    return "junit";
  }

  public boolean skipTrace(final Description description) {
    return description.getAnnotation(DisableTestTrace.class) != null
        || description.getTestClass().getAnnotation(DisableTestTrace.class) != null;
  }

  public void onTestStart(final AgentSpan span, final Description description) {
    onTestStart(span, description, null);
  }

  public void onTestStart(
      final AgentSpan span, final Description description, final String testNameArg) {
    final String testSuite = description.getClassName();
    final String testName = (testNameArg != null) ? testNameArg : description.getMethodName();

    span.setTag(DDTags.RESOURCE_NAME, testSuite + "." + testName);
    span.setTag(DDTags.TEST_SUITE, testSuite);
    span.setTag(DDTags.TEST_NAME, testName);
    // We cannot set TEST_PASS status in onTestFinish(...) method because that method
    // is executed always after onTestFailure. For that reason, TEST_PASS status is preset
    // in onTestStart.
    span.setTag(DDTags.TEST_STATUS, TEST_PASS);
  }

  public void onTestFinish(final AgentSpan span) {}

  public void onTestFailure(final AgentSpan span, final Failure failure) {
    final Throwable throwable = failure.getException();
    if (throwable != null) {
      span.setError(true);
      span.addThrowable(throwable);
      span.setTag(DDTags.TEST_STATUS, TEST_FAIL);
    }
  }

  public void onTestIgnored(
      final AgentSpan span,
      final Description description,
      final String testName,
      final String reason) {
    onTestStart(span, description, testName);
    span.setTag(DDTags.TEST_STATUS, TEST_SKIP);
    span.setTag(DDTags.TEST_SKIP_REASON, reason);
  }
}
