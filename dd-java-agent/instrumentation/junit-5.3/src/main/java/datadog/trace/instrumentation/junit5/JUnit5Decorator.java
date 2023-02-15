package datadog.trace.instrumentation.junit5;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestTag;
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

  public void onTestModuleStart(final AgentSpan span, final String version) {
    afterTestModuleStart(span, version);
  }

  public void onTestModuleFinish(final AgentSpan span) {
    beforeTestModuleFinish(span);
  }

  // sometimes JUnit invokes multiple nested runners with the same suite class
  // (this happens, for instance, for parameterized tests)
  // in this case we maintain a counter to determine which runner invocation is the first,
  // and to only trigger processing once
  public boolean tryTestSuiteStart(final TestIdentifier testIdentifier) {
    Class<?> testClass = JUnit5Utils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();
    return tryTestSuiteStart(testSuiteName, testClass);
  }

  // sometimes JUnit invokes multiple nested runners with the same suite class
  // (this happens, for instance, for parameterized tests)
  // in this case we maintain a counter to determine which runner invocation is the first,
  // and to only trigger processing once
  public boolean tryTestSuiteFinish(final TestIdentifier testIdentifier) {
    Class<?> testClass = JUnit5Utils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();
    return tryTestSuiteFinish(testSuiteName, testClass);
  }

  public void onTestSuiteStart(
      final AgentSpan span, final String version, final TestIdentifier testIdentifier) {
    Class<?> testClass = JUnit5Utils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();
    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());

    afterTestSuiteStart(span, testSuiteName, version, testClass, tags);
  }

  public void onTestSuiteFinish(final AgentSpan span, final TestIdentifier testIdentifier) {
    Class<?> testClass = JUnit5Utils.getJavaClass(testIdentifier);
    String testSuiteName =
        testClass != null ? testClass.getName() : testIdentifier.getLegacyReportingName();
    beforeTestSuiteFinish(span, testSuiteName, testClass);
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

    List<String> tags =
        testIdentifier.getTags().stream().map(TestTag::getName).collect(Collectors.toList());
    afterTestStart(
        span, testSuitName, testName, testParameters, version, testClass, testMethod, tags);

    span.setTag(Tags.TEST_STATUS, TEST_PASS);
  }

  public void onTestFinish(final AgentSpan span, final MethodSource methodSource) {
    String testSuiteName = methodSource.getClassName();
    Class<?> testClass = JUnit5Utils.getTestClass(methodSource);
    beforeTestFinish(span, testSuiteName, testClass);
  }

  public void onPossibleFailure(AgentSpan span, TestExecutionResult result) {
    result
        .getThrowable()
        .ifPresent(
            throwable -> {
              if (JUnit5Utils.isAssumptionFailure(throwable)) {
                span.setTag(Tags.TEST_STATUS, TEST_SKIP);
                span.setTag(Tags.TEST_SKIP_REASON, throwable.getMessage());
              } else {
                span.setError(true);
                span.addThrowable(throwable);
                span.setTag(Tags.TEST_STATUS, TEST_FAIL);
              }
            });
  }
}
