package datadog.trace.agent.test.scopediag;

/**
 * A single observed point in a continuation's lifecycle. Time, thread, and stack are captured by
 * the recorder on the event's own thread (notifications are synchronous), so they reflect the
 * thread that actually captured/activated/resolved the continuation.
 */
public final class ScopeEvent {
  public enum Type {
    CAPTURE,
    ACTIVATE,
    RESOLVE_FINISH,
    RESOLVE_CANCEL
  }

  public final Type type;
  public final String threadName;
  public final long nanos;
  public final StackTraceElement[] stack;

  ScopeEvent(Type type, String threadName, long nanos, StackTraceElement[] stack) {
    this.type = type;
    this.threadName = threadName;
    this.nanos = nanos;
    this.stack = stack;
  }

  /** The most relevant (top, post-filter) frame, or {@code null} if none survived filtering. */
  public StackTraceElement callsite() {
    return stack != null && stack.length > 0 ? stack[0] : null;
  }
}
