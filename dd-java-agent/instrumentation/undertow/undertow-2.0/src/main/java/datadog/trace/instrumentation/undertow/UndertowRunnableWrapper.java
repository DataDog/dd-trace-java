package datadog.trace.instrumentation.undertow;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;

import datadog.context.Context;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import io.undertow.server.HttpServerExchange;

public class UndertowRunnableWrapper implements Runnable {

  private Runnable runnable;
  private HttpServerExchange exchange;
  private ContextContinuation continuation;

  public UndertowRunnableWrapper(
      Runnable runnable, HttpServerExchange exchange, ContextContinuation continuation) {
    this.runnable = runnable;
    this.exchange = exchange;
    this.continuation = continuation;
  }

  @Override
  public void run() {
    try (ContextScope scope = continuation.resume()) {
      runnable.run();
    }
  }

  public static Runnable wrapIfNeeded(final Runnable task, HttpServerExchange exchange) {
    if (task instanceof UndertowRunnableWrapper || exclude(RUNNABLE, task)) {
      return task;
    }
    ContextContinuation continuation = captureActiveSpan();
    if (continuation.context() != Context.root()) {
      return new UndertowRunnableWrapper(task, exchange, continuation);
    }
    return task; // don't wrap unless there is a span to propagate
  }
}
