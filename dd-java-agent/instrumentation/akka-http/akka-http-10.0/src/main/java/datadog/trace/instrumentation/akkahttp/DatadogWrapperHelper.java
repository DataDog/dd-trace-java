package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.DECORATE;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class DatadogWrapperHelper {
  public static ContextScope createSpan(final HttpRequest request) {
    final Context extractedContext = DECORATE.extractContext(request);
    final AgentSpan span = DECORATE.startSpan(request, extractedContext);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request, request, extractedContext);

    return extractedContext.with(span).attach();
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
