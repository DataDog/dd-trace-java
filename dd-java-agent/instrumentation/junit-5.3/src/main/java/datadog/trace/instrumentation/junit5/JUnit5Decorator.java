package datadog.trace.instrumentation.junit5;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import datadog.trace.util.Strings;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

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

  public void onTestStart(
      final AgentSpan span, final MethodSource methodSource, final TestIdentifier testIdentifier) {
    onTestStart(span, methodSource.getClassName(), methodSource.getMethodName());

    if (hasParameters(methodSource)) {
      // No public access to the test parameters map in JUnit5.
      // In this case, we store the displayName in the "metadata.test_name" object.
      // The test display name used to have the parameters.
      span.setTag(
          Tags.TEST_PARAMETERS,
          "{\"metadata\":{\"test_name\":\""
              + Strings.escapeToJson(testIdentifier.getDisplayName())
              + "\"}}");
    }
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
              switch (throwable.getClass().getName()) {
                case "org.junit.AssumptionViolatedException":
                case "org.junit.internal.AssumptionViolatedException":
                case "org.opentest4j.TestAbortedException":
                case "org.opentest4j.TestSkippedException":
                  // If the test assumption fails, one of the following exceptions will be thrown.
                  // The consensus is to treat "assumptions failure" as skipped tests.
                  span.setTag(Tags.TEST_STATUS, TEST_SKIP);
                  span.setTag(Tags.TEST_SKIP_REASON, throwable.getMessage());
                  break;
                default:
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

  private boolean hasParameters(final MethodSource methodSource) {
    return methodSource.getMethodParameterTypes() != null
        && !methodSource.getMethodParameterTypes().isEmpty();
  }
}
