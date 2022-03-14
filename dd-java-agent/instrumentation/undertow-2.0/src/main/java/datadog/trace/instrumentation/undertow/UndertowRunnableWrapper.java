package datadog.trace.instrumentation.undertow;

import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import io.undertow.server.HttpServerExchange;

public class UndertowRunnableWrapper extends RunnableWrapper {

  private HttpServerExchange exchange;
  private AgentSpan span;

  public UndertowRunnableWrapper(Runnable runnable, HttpServerExchange exchange, AgentSpan span) {
    super(runnable);
    this.exchange = exchange;
    this.span = span;
  }

  @Override
  public void run() {
    try {
      super.run();
      DECORATE.onResponse(span, exchange);
      DECORATE.beforeFinish(span);
      span.finish();
    } catch (Throwable throwable) {
      DECORATE.onError(span, throwable);
      DECORATE.onResponse(span, exchange);
      DECORATE.beforeFinish(span);
      span.finish();
      throw throwable;
    }
  }

  public static Runnable wrapIfNeeded(
      final Runnable task, HttpServerExchange exchange, AgentSpan span) {
    if (!(task instanceof RunnableWrapper)
        && !ExcludeFilter.exclude(ExcludeFilter.ExcludeType.RUNNABLE, task)) {
      return new UndertowRunnableWrapper(task, exchange, span);
    }
    return task;
  }
}
