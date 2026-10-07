package datadog.trace.instrumentation.netty40.concurrent;

import datadog.context.ContextScope;

/** Transfers the runTask() scope to run() so completion listeners retain the task context. */
public final class ScheduledTaskScope {
  public static final ThreadLocal<ScheduledTaskScope> CURRENT = new ThreadLocal<>();

  public ContextScope scope;
  // Track activations separately: resuming the same context can reuse the outer scope instance.
  public int depth;
}
