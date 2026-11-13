package datadog.trace.instrumentation.mongo;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/**
 * Hands a pending span from command advice to the synchronous command-start listener on the same
 * thread. The advice's call-depth guard allows only one pending command per thread.
 */
public final class MongoPendingSpan {
  private static final ThreadLocal<AgentSpan> CURRENT = new ThreadLocal<>();

  private MongoPendingSpan() {}

  public static void enter(AgentSpan span) {
    CURRENT.set(span);
  }

  /** Transfers ownership of the pending span to the command listener. */
  public static AgentSpan take() {
    AgentSpan span = CURRENT.get();
    if (span != null) {
      CURRENT.set(null);
    }
    return span;
  }

  /**
   * Finishes an unclaimed span when the driver exits before notifying the command-start listener.
   */
  public static void onExit(AgentSpan span, Throwable error) {
    if (span != null && CURRENT.get() == span) {
      CURRENT.set(null);
      if (error != null) {
        span.addThrowable(error);
      }
      span.finish();
    }
  }
}
