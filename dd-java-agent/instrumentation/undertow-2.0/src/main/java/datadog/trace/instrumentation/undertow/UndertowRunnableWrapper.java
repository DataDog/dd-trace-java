package datadog.trace.instrumentation.undertow;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import io.undertow.server.HttpServerExchange;

import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;

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
    boolean thrown = false;
    try {
      super.run();
    } catch (Throwable throwable) {
      thrown = true;
      DECORATE.onError(span, throwable);
      DECORATE.onResponse(span, exchange);
      DECORATE.beforeFinish(span);
      span.finish();
      throw throwable;
    } finally {
      if (!thrown) {
        DECORATE.onResponse(span, exchange);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }

  public static Runnable wrapIfNeeded(final Runnable task, HttpServerExchange exchange, AgentSpan span) {
    if (!(task instanceof RunnableWrapper) && !ExcludeFilter.exclude(ExcludeFilter.ExcludeType.RUNNABLE, task)) {
      return new UndertowRunnableWrapper(task, exchange, span);
    }
    return task;
  }

}
