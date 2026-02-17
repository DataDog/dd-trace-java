package datadog.trace.civisibility.execution.exit;

/** Policy that ignores early exit on retries. */
public class NoExit implements EarlyExitPolicy {
  public static final EarlyExitPolicy INSTANCE = new NoExit();

  private NoExit() {}

  @Override
  public boolean evaluate(boolean hasFailedExecutions, boolean hasPassedExecutions) {
    return false;
  }
}
