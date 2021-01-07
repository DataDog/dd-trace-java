package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.AKKA_REQUEST;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerHeaders.GETTER;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;

public class DatadogWrapperHelper {
  public static AgentScope createSpan(final HttpRequest request) {
    final AgentSpan.Context extractedContext = propagate().extract(request, GETTER);
    final AgentSpan span = startSpan(AKKA_REQUEST, extractedContext);
    span.setMeasured(true);

    DECORATE.afterStart(span);
    DECORATE.onConnection(span, request);
    DECORATE.onRequest(span, request);

    final AgentScope scope = activateSpan(span);
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
    span.setTag(Tags.HTTP_STATUS, 500);
    DECORATE.beforeFinish(span);

    span.finish();
  }
}
