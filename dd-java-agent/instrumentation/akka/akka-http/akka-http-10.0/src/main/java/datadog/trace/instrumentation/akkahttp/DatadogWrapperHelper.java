package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.DECORATE;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class DatadogWrapperHelper {
  public static ContextScope createSpan(final HttpRequest request) {
    final Context parentContext = DECORATE.extract(request);
    final Context context = DECORATE.startSpan(request, parentContext);
    final AgentSpan span = fromContext(context);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request, request, parentContext);

    return context.attach();
  }

  public static void finishSpan(final ContextScope scope, final HttpResponse response) {
    final AgentSpan span = fromContext(scope.context());
    DECORATE.onResponse(span, response);
    DECORATE.beforeFinish(scope.context());

    span.finish();
  }

  public static void finishSpan(final ContextScope scope, final Throwable t) {
    final AgentSpan span = fromContext(scope.context());
    DECORATE.onError(span, t);
    span.setHttpStatusCode(500);
    DECORATE.beforeFinish(scope.context());

    span.finish();
  }
}
