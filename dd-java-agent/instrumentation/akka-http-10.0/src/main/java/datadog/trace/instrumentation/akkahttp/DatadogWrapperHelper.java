package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateContext;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.DECORATE;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class DatadogWrapperHelper {
  public static AgentScope createSpan(final HttpRequest request) {
    final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(request);
    final AgentScopeContext context = DECORATE.startSpanContext(request, extractedContext);
    DECORATE.afterStart(context.span());
    DECORATE.onRequest(context.span(), request, request, extractedContext);

    final AgentScope scope = activateContext(context);
    scope.setAsyncPropagation(true);
    return scope;
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
