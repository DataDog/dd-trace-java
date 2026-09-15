package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.DECORATE;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class DatadogWrapperHelper {
  private static final boolean LEGACY_CONTEXT_MANAGER_ENABLED =
      InstrumenterConfig.get().isLegacyContextManagerEnabled();

  public static ContextScope createSpan(final HttpRequest request) {
    return startSpan(request).attach();
  }

  public static ContextScope createSpanForFlow(final HttpRequest request) {
    final Context context = startSpan(request);
    if (LEGACY_CONTEXT_MANAGER_ENABLED) {
      return context.attach();
    }
    context.swap();
    return context.asScope();
  }

  private static Context startSpan(final HttpRequest request) {
    final Context parentContext = DECORATE.extract(request);
    final Context context = DECORATE.startSpan(request, parentContext);
    final AgentSpan span = fromContext(context);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request, request, parentContext);

    return context;
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
