package datadog.trace.civisibility.execution.exit;

/** Policy to avoid retries once a test fails once. Used for Attempt to Fix. */
public class ExitOnFailure implements EarlyExitPolicy {
  public static final EarlyExitPolicy INSTANCE = new ExitOnFailure();

  private ExitOnFailure() {}

  @Override
  public boolean evaluate(boolean hasFailedExecutions, boolean hasPassedExecutions) {
    return hasFailedExecutions;
  }
}
