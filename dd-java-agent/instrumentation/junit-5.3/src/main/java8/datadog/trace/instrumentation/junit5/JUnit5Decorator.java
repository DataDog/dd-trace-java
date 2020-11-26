package datadog.trace.instrumentation.junit5;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;
import datadog.trace.util.MurmurHash2;
import lombok.extern.slf4j.Slf4j;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;

@Slf4j
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
    span.setTag(DDTags.RESOURCE_NAME, testSuite + "." + testName);
    span.setTag(Tags.TEST_SUITE, testSuite);
    span.setTag(Tags.TEST_NAME, testName);
    span.setTag(Tags.TEST_FINGERPRINT, MurmurHash2.hash64(testSuite + "." + testName));
    span.setTag(Tags.TEST_STATUS, TEST_PASS);
  }

  public void onTestFinish(final AgentSpan span, final TestExecutionResult result) {
    result
        .getThrowable()
        .ifPresent(
            throwable -> {
              span.setError(true);
              span.addThrowable(throwable);
              span.setTag(Tags.TEST_STATUS, TEST_FAIL);
            });
  }

  public void onTestIgnore(
      final AgentSpan span, final String testSuite, final String testName, final String reason) {
    onTestStart(span, testSuite, testName);
    span.setTag(Tags.TEST_STATUS, TEST_SKIP);
    span.setTag(Tags.TEST_SKIP_REASON, reason);
  }
}
