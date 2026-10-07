package datadog.trace.agent.test.scopediag;

/** A timestamped scope or continuation lifecycle event with its thread and call site. */
public final class ScopeEvent {
  public enum Type {
    CAPTURE,
    ACTIVATE,
    /** An {@code activate()} that returned the noop scope after the continuation was resolved. */
    ACTIVATE_FAILED,
    RESOLVE_FINISH,
    RESOLVE_RELEASE,
    SCOPE_OPEN,
    SCOPE_CLOSE,
    /** A scope close was attempted from a thread other than its owner. */
    SCOPE_CLOSE_WRONG_THREAD,
    /** The owner attempted to close a scope that was not on top of its stack. */
    SCOPE_CLOSE_OUT_OF_ORDER
  }

  public final Type type;
  public final String threadName;
  public final long threadId;
  public final long nanos;
  public final StackTraceElement[] stack;

  ScopeEvent(Type type, String threadName, long nanos, StackTraceElement[] stack) {
    this(type, threadName, Thread.currentThread().getId(), nanos, stack);
  }

  ScopeEvent(Type type, String threadName, long threadId, long nanos, StackTraceElement[] stack) {
    this.threadId = threadId;
    this.type = type;
    this.threadName = threadName;
    this.nanos = nanos;
    this.stack = stack;
  }

  ScopeEvent snapshot() {
    return new ScopeEvent(type, threadName, threadId, nanos, stack == null ? null : stack.clone());
  }

  /** The most relevant (top, post-filter) frame, or {@code null} if none survived filtering. */
  public StackTraceElement callsite() {
    return stack != null && stack.length > 0 ? stack[0] : null;
  }
}
