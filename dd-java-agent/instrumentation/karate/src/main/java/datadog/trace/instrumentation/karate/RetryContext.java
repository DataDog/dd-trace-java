package datadog.trace.instrumentation.karate;

import com.intuit.karate.core.Scenario;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;

public class RetryContext {

  private final TestRetryPolicy retryPolicy;
  private boolean failed;

  public RetryContext(TestRetryPolicy retryPolicy) {
    this.retryPolicy = retryPolicy;
  }

  public void setFailed(boolean failed) {
    this.failed = failed;
  }

  public boolean getAndResetFailed() {
    boolean failed = this.failed;
    this.failed = false;
    return failed;
  }

  public TestRetryPolicy getRetryPolicy() {
    return retryPolicy;
  }

  public static RetryContext create(Scenario scenario) {
    TestIdentifier testIdentifier = KarateUtils.toTestIdentifier(scenario, false);
    return new RetryContext(
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.retryPolicy(testIdentifier));
  }
}
