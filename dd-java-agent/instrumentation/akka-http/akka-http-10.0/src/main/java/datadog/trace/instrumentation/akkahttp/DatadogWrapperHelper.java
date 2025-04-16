package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.DECORATE;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

public class DatadogWrapperHelper {
  public static AgentScope createSpan(final HttpRequest request) {
    final Context extractedContext = DECORATE.extract(request);
    AgentSpan extractedSpan = AgentSpan.fromContext(extractedContext);
    AgentSpanContext.Extracted extractedSpanContext =
        extractedSpan == null ? null : (AgentSpanContext.Extracted) extractedSpan.context();
    final AgentSpan span = DECORATE.startSpan(request, extractedSpanContext);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request, request, extractedSpanContext);

    return (AgentScope) extractedContext.with(span).attach();
  }

  public static void finishSpan(final AgentSpan span, final HttpResponse response) {
    DECORATE.onResponse(span, response);
    DECORATE.beforeFinish(span);

    span.finish();
  }

  public static void finishSpan(final AgentSpan span, final Throwable t) {
    DECORATE.onError(span, t);
    span.setHttpStatusCode(500);
    DECORATE.beforeFinish(span);

    span.finish();
  }
}
