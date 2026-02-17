package datadog.trace.civisibility.execution.exit;

/**
 * Policy to avoid retries once a test flakes (has at least one failed and passed execution). Used
 * for Early Flake Detection.
 */
public class ExitOnFlake implements EarlyExitPolicy {

  public static final EarlyExitPolicy INSTANCE = new ExitOnFlake();

  private ExitOnFlake() {}

  @Override
  public boolean evaluate(boolean hasFailedExecutions, boolean hasPassedExecutions) {
    return hasFailedExecutions && hasPassedExecutions;
  }
}
