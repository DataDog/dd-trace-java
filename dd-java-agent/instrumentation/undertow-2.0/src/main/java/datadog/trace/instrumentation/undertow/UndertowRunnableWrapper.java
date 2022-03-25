package datadog.trace.instrumentation.undertow;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpServerExchange;

public class UndertowRunnableWrapper implements Runnable {

  private Runnable runnable;
  private HttpServerExchange exchange;
  private AgentScope.Continuation continuation;

  public UndertowRunnableWrapper(
      Runnable runnable, HttpServerExchange exchange, AgentScope.Continuation continuation) {
    this.runnable = runnable;
    this.exchange = exchange;
    this.continuation = continuation;
    continuation.migrate();
  }

  @Override
  public void run() {
    AgentSpan span = continuation.getSpan();
    try (AgentScope scope = continuation.activate()) {
      runnable.run();
    } catch (Throwable throwable) {
      DECORATE.onError(span, throwable);
      throw throwable;
    } finally {
      DECORATE.onResponse(span, exchange);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  public static Runnable wrapIfNeeded(final Runnable task, HttpServerExchange exchange) {
    if (task instanceof UndertowRunnableWrapper || exclude(RUNNABLE, task)) {
      return task;
    }
    AgentScope scope = activeScope();
    if (null != scope) {
      return new UndertowRunnableWrapper(task, exchange, scope.capture());
    }
    return task; // don't wrap unless there is a scope to propagate
  }
}
