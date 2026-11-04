package datadog.trace.instrumentation.pekkohttp;

import static datadog.context.Context.isCaptureEnabled;
import static datadog.context.Context.setCaptureEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.instrumentation.pekkohttp.PekkoHttpServerDecorator.DECORATE;
import static java.util.Collections.singletonList;

import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;

public class DatadogWrapperHelper {
  private static final boolean ROUTE_CAPTURE_ENABLED =
      InstrumenterConfig.get()
          .isIntegrationEnabled(
              singletonList("pekko-http-route"),
              InstrumenterConfig.get().isIntegrationsEnabled()
                  && Config.get().isTraceOtelSemanticsEnabled());

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
    return new SwappedContextScope(context);
  }

  private static Context startSpan(final HttpRequest request) {
    final Context parentContext = DECORATE.extract(request);
    final Context context = DECORATE.startSpan(request, parentContext);
    final AgentSpan span = fromContext(context);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request, request, parentContext);

    if (ROUTE_CAPTURE_ENABLED) {
      return PekkoHttpRouteState.init(context, request.uri().path());
    } else {
      return context;
    }
  }

  static final class SwappedContextScope implements ContextScope {
    private final Context context;
    private final ContextHandle previousContext;
    private final Thread ownerThread;
    private boolean closed;

    SwappedContextScope(final Context context) {
      this.context = context;
      this.ownerThread = Thread.currentThread();
      boolean allowCapture = isCaptureEnabled();
      this.previousContext = context.swap();
      // Request handling inherits the enclosing flow's capture flag.
      setCaptureEnabled(allowCapture);
    }

    @Override
    public Context context() {
      return context;
    }

    @Override
    public void close() {
      if (!closed && ownerThread == Thread.currentThread() && context == Context.current()) {
        closed = true;
        previousContext.swap();
      }
    }
  }

  public static void finishSpan(final Context context, final HttpResponse response) {
    final AgentSpan span = fromContext(context);
    if (ROUTE_CAPTURE_ENABLED) {
      PekkoHttpRouteState.onComplete(context, span);
    }
    try {
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(context);
    } finally {
      span.finish();
    }
  }

  public static void finishSpan(final Context context, final Throwable t) {
    final AgentSpan span = fromContext(context);
    if (ROUTE_CAPTURE_ENABLED) {
      PekkoHttpRouteState.onComplete(context, span);
    }
    try {
      DECORATE.onError(span, t);
      span.setHttpStatusCode(500);
      DECORATE.beforeFinish(context);
    } finally {
      span.finish();
    }
  }
}
