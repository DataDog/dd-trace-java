package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;

public class JettyRunnableWrapper implements Runnable {

  private Runnable runnable;
  private AgentScope.Continuation continuation;

  public JettyRunnableWrapper(Runnable runnable, AgentScope.Continuation continuation) {
    this.runnable = runnable;
    this.continuation = continuation;
  }

  @Override
  public void run() {
    try (AgentScope scope = continuation.activate()) {
      runnable.run();
    }
  }

  public static Runnable wrapIfNeeded(final Runnable task) {
    if (task instanceof JettyRunnableWrapper || exclude(RUNNABLE, task)) {
      return task;
    }
    AgentScope.Continuation continuation = captureActiveSpan();
    if (continuation != noopContinuation()) {
      return new JettyRunnableWrapper(task, continuation);
    }
    return task; // don't wrap unless there is a scope to propagate
  }
}
