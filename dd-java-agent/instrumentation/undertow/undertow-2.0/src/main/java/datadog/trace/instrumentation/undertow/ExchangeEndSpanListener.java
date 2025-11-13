package datadog.trace.instrumentation.undertow;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_UNDERTOW_CONTINUATION;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.DefaultResponseListener;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpServerExchange;

public class ExchangeEndSpanListener implements ExchangeCompletionListener {
  public static final ExchangeEndSpanListener INSTANCE = new ExchangeEndSpanListener();

  private ExchangeEndSpanListener() {}

  @Override
  public void exchangeEvent(HttpServerExchange exchange, NextListener nextListener) {
    AgentScope.Continuation continuation = exchange.getAttachment(DD_UNDERTOW_CONTINUATION);
    if (continuation == null) {
      return;
    }

    Context context = continuation.context();
    AgentSpan span = fromContext(context);
    if (span != null) {
      Throwable throwable = exchange.getAttachment(DefaultResponseListener.EXCEPTION);
      if (throwable != null) {
        DECORATE.onError(span, throwable);
      }
      DECORATE.onResponse(span, exchange);
      DECORATE.beforeFinish(context);
      span.finish();
    } else {
      DECORATE.beforeFinish(context);
    }

    continuation.cancel();
    nextListener.proceed();
  }
}
