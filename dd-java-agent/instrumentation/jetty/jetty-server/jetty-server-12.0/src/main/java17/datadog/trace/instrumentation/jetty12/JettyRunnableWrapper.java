package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import org.eclipse.jetty.util.thread.Invocable;

public class JettyRunnableWrapper implements Runnable, Invocable {

  private Runnable runnable;
  private ContextContinuation continuation;

  public JettyRunnableWrapper(Runnable runnable, ContextContinuation continuation) {
    this.runnable = runnable;
    this.continuation = continuation;
  }

  @Override
  public InvocationType getInvocationType() {
    return Invocable.getInvocationType(runnable);
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
    ContextContinuation continuation = Context.current().capture();
    if (continuation.context() != Context.root()) {
      return new JettyRunnableWrapper(task, continuation);
    }
    return task; // don't wrap unless there is a scope to propagate
  }
}
