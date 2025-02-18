package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.pekkohttp.PekkoHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;

public class DatadogWrapperHelper {
  public static AgentScope createSpan(final HttpRequest request) {
    final AgentSpanContext.Extracted extractedContext = DECORATE.extract(request);
    final AgentSpan span = DECORATE.startSpan(request, extractedContext);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request, request, extractedContext);

    return activateSpan(span, true);
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
