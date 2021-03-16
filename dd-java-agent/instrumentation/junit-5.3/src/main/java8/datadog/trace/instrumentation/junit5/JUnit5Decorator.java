package datadog.trace.instrumentation.junit5;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;

public class JUnit5Decorator extends TestDecorator {

  public static final JUnit5Decorator DECORATE = new JUnit5Decorator();

  @Override
  public String testFramework() {
    return "junit5";
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"junit", "junit-5"};
  }

  @Override
  public String component() {
    return "junit";
  }

  public void onTestStart(final AgentSpan span, final MethodSource methodSource) {
    onTestStart(span, methodSource.getClassName(), methodSource.getMethodName());
  }

  public void onTestStart(final AgentSpan span, final String testSuite, final String testName) {
    span.setResourceName(testSuite + "." + testName);
    span.setTag(Tags.TEST_SUITE, testSuite);
    span.setTag(Tags.TEST_NAME, testName);
    span.setTag(Tags.TEST_STATUS, TEST_PASS);
  }

  public void onTestFinish(final AgentSpan span, final TestExecutionResult result) {
    result
        .getThrowable()
        .ifPresent(
            throwable -> {
              // If the test assumption fails, one of the following exceptions will be thrown.
              // The consensus is to treat "assumptions failure" as skipped tests.
              if (throwable.getClass().getName().equals("org.opentest4j.TestAbortedException")
                  || throwable.getClass().getName().equals("org.opentest4j.TestSkippedException")) {
                span.setTag(Tags.TEST_STATUS, TEST_SKIP);
                span.setTag(Tags.TEST_SKIP_REASON, throwable.getMessage());
              } else {
                span.setError(true);
                span.addThrowable(throwable);
                span.setTag(Tags.TEST_STATUS, TEST_FAIL);
              }
            });
  }

  public void onTestIgnore(
      final AgentSpan span, final String testSuite, final String testName, final String reason) {
    onTestStart(span, testSuite, testName);
    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    span.setTag(Tags.TEST_SKIP_REASON, reason);
  }
}
