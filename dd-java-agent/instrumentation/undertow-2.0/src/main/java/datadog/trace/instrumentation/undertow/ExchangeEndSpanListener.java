package datadog.trace.instrumentation.undertow;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.DefaultResponseListener;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpServerExchange;

import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_HTTPSERVEREXCHANGE_DISPATCH;

public class ExchangeEndSpanListener implements ExchangeCompletionListener {
  private final AgentSpan span;

  public ExchangeEndSpanListener(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void exchangeEvent(HttpServerExchange exchange, NextListener nextListener) {
    boolean dispatched = exchange.getAttachment(DD_HTTPSERVEREXCHANGE_DISPATCH);
    if (dispatched) {
      Throwable throwable = exchange.getAttachment(DefaultResponseListener.EXCEPTION);
      DECORATE.onError(span, throwable);

      DECORATE.onResponse(span, exchange);
      DECORATE.beforeFinish(span);
      span.finish();
    }
    nextListener.proceed();
  }
}
