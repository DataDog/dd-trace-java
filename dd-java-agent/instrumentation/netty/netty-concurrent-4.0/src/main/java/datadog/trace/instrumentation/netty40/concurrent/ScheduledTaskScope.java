package datadog.trace.instrumentation.netty40.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;

import datadog.context.ContextScope;

/** Keeps the task context active through run() completion listeners. */
public final class ScheduledTaskScope {
  public static final ThreadLocal<ScheduledTaskScope> CURRENT = new ThreadLocal<>();

  private ContextScope scope;
  // Track activations separately: resuming the same context can reuse the outer scope instance.
  private int depth;

  public ContextScope scope() {
    return scope;
  }

  public int depth() {
    return depth;
  }

  public static void activate(ContextScope scope) {
    if (scope != null) {
      ScheduledTaskScope state = CURRENT.get();
      if (state == null) {
        state = new ScheduledTaskScope();
        CURRENT.set(state);
      }
      state.scope = scope;
      state.depth++;
    }
  }

  public static void close(ContextScope previous, int depth) {
    ScheduledTaskScope state = CURRENT.get();
    if (state != null && state.depth > depth) {
      ContextScope scope = state.scope;
      state.scope = previous;
      state.depth = depth;
      endTaskScope(scope);
    }
  }
}
