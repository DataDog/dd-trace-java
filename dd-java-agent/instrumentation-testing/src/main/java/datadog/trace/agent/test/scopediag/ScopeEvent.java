package datadog.trace.agent.test.scopediag;

/** A timestamped scope or continuation lifecycle event with its thread and call site. */
public final class ScopeEvent {
  public enum Type {
    CAPTURE,
    ACTIVATE,
    /** An {@code activate()} that returned the noop scope after the continuation was resolved. */
    ACTIVATE_FAILED,
    RESOLVE_FINISH,
    RESOLVE_CANCEL,
    SCOPE_OPEN,
    SCOPE_CLOSE,
    /** A scope was closed while not on top of its thread's stack. */
    SCOPE_CLOSE_WRONG_THREAD
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

  ScopeEvent snapshot() {
    return new ScopeEvent(type, threadName, nanos, stack == null ? null : stack.clone());
  }

  /** The most relevant (top, post-filter) frame, or {@code null} if none survived filtering. */
  public StackTraceElement callsite() {
    return stack != null && stack.length > 0 ? stack[0] : null;
  }
}
