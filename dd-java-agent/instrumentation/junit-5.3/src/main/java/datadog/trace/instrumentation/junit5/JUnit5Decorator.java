package datadog.trace.instrumentation.junit5;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import java.lang.reflect.Method;
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
      final AgentSpan span,
      final String version,
      final MethodSource methodSource,
      final TestIdentifier testIdentifier) {
    String testSuitName = methodSource.getClassName();
    String testName = methodSource.getMethodName();
    String testParameters = JUnit5Utils.getParameters(methodSource, testIdentifier);

    Class<?> testClass = JUnit5Utils.getTestClass(methodSource);
    Method testMethod = JUnit5Utils.getTestMethod(methodSource);

    afterTestStart(
        span, testSuitName, testName, testParameters, version, testClass, testMethod, null);

    span.setTag(Tags.TEST_STATUS, TEST_PASS);
  }

  public void onTestIgnore(
      final AgentSpan span,
      final String version,
      final MethodSource methodSource,
      final String reason) {
    String testSuiteName = methodSource.getClassName();
    String testName = methodSource.getMethodName();

    Class<?> testClass = JUnit5Utils.getTestClass(methodSource);
    Method testMethod = JUnit5Utils.getTestMethod(methodSource);

    afterTestStart(span, testSuiteName, testName, null, version, testClass, testMethod, null);

    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    span.setTag(Tags.TEST_SKIP_REASON, reason);

    beforeTestFinish(span, testSuiteName, testClass);
  }

  public void onTestFinish(
      final AgentSpan span, final MethodSource methodSource, final TestExecutionResult result) {
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

    String testSuiteName = methodSource.getClassName();
    Class<?> testClass = JUnit5Utils.getTestClass(methodSource);
    beforeTestFinish(span, testSuiteName, testClass);
  }
}
