package datadog.trace.instrumentation.undertow;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_UNDERTOW_CONTINUATION;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;

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

    AgentSpan span = continuation.span();

    Throwable throwable = exchange.getAttachment(DefaultResponseListener.EXCEPTION);
    if (throwable != null) {
      DECORATE.onError(span, throwable);
    }
    if (exchange.isUpgrade() && UndertowDecorator.UNDERTOW_LEGACY_TRACING) {
      // make sure the resource is set correctly when upgrade is performed.
      // we set it in the ServletInstrumentation but it's too early
      HTTP_RESOURCE_DECORATOR.withRoute(
          span, exchange.getRequestMethod().toString(), exchange.getRelativePath(), false);
    }

    DECORATE.onResponse(span, exchange);
    DECORATE.beforeFinish(span);
    continuation.cancel();
    span.finish();
    nextListener.proceed();
  }
}
