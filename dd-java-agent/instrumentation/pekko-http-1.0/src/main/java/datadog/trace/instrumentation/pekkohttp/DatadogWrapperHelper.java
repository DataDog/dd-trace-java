package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.instrumentation.pekkohttp.PekkoHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;

public class DatadogWrapperHelper {
  public static ContextScope createSpan(final HttpRequest request) {
    final Context context = DECORATE.extract(request);
    final AgentSpan span = DECORATE.startSpan(request, context);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request, request, context);

    return context.with(span).attach();
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
