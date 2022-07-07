package datadog.trace.instrumentation.undertow;

import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpServerExchange;

public class ExchangeEndSpanListener implements ExchangeCompletionListener {
  private final AgentSpan span;

  public ExchangeEndSpanListener(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void exchangeEvent(HttpServerExchange exchange, NextListener nextListener) {
    DECORATE.onResponse(span, exchange);
    DECORATE.beforeFinish(span);
    span.finish();
    nextListener.proceed();
  }
}
