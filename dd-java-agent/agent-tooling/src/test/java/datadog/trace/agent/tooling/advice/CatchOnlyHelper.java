package datadog.trace.agent.tooling.advice;

public final class CatchOnlyHelper {
  public static void run() {
    try {
      System.nanoTime();
    } catch (CatchOnlyException ignored) {
    } finally {
      System.nanoTime();
    }
  }
}
