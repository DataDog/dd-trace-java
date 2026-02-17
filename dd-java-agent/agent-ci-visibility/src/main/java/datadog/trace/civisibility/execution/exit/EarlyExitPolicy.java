package datadog.trace.civisibility.execution.exit;

public interface EarlyExitPolicy {

  /**
   * @return {@code true} if the policy indicates that the test should not be retried anymore.
   */
  boolean evaluate(boolean hasFailedExecutions, boolean hasPassedExecutions);
}
