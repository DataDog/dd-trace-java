package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.instrumentation.pekkohttp.PekkoHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;

public class DatadogWrapperHelper {
  public static ContextScope createSpan(final HttpRequest request) {
    final Context parentContext = DECORATE.extract(request);
    final Context context = DECORATE.startSpan(request, parentContext);
    final AgentSpan span = fromContext(context);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request, request, parentContext);

    return context.attach();
  }

  public static void finishSpan(final Context context, final HttpResponse response) {
    final AgentSpan span = fromContext(context);
    DECORATE.onResponse(span, response);
    DECORATE.beforeFinish(context);

    span.finish();
  }

  public static void finishSpan(final Context context, final Throwable t) {
    final AgentSpan span = fromContext(context);
    DECORATE.onError(span, t);
    span.setHttpStatusCode(500);
    DECORATE.beforeFinish(context);

    span.finish();
  }
}
