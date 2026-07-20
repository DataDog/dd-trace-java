package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.context.ContextContinuation;
import datadog.context.ContextScope;

public class JettyRunnableWrapper implements Runnable {

  private Runnable runnable;
  private ContextContinuation continuation;

  public JettyRunnableWrapper(Runnable runnable, ContextContinuation continuation) {
    this.runnable = runnable;
    this.continuation = continuation;
  }

  @Override
  public void run() {
    try (ContextScope scope = continuation.resume()) {
      runnable.run();
    }
  }

  public static Runnable wrapIfNeeded(final Runnable task) {
    if (task instanceof JettyRunnableWrapper || exclude(RUNNABLE, task)) {
      return task;
    }
    ContextContinuation continuation = captureActiveSpan();
    if (continuation != noopContinuation()) {
      return new JettyRunnableWrapper(task, continuation);
    }
    return task; // don't wrap unless there is a scope to propagate
  }
}
