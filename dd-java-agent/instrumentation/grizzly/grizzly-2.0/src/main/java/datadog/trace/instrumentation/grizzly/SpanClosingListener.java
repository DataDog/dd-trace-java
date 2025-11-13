package datadog.trace.instrumentation.grizzly;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.grizzly.GrizzlyDecorator.DECORATE;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.glassfish.grizzly.http.server.AfterServiceListener;
import org.glassfish.grizzly.http.server.Request;

public class SpanClosingListener implements AfterServiceListener {
  public static final SpanClosingListener LISTENER = new SpanClosingListener();

  @Override
  public void onAfterService(final Request request) {
    final Object contextAttr = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (contextAttr instanceof Context) {
      request.removeAttribute(DD_CONTEXT_ATTRIBUTE);

      final Context context = (Context) contextAttr;
      final AgentSpan span = spanFromContext(context);
      if (span != null) {
        DECORATE.onResponse(span, request.getResponse());
        DECORATE.beforeFinish(context);
        span.finish();
      } else {
        DECORATE.beforeFinish(context);
      }
    }
  }
}
