package datadog.trace.instrumentation.grizzly;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.grizzly.GrizzlyDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.glassfish.grizzly.http.server.AfterServiceListener;
import org.glassfish.grizzly.http.server.Request;

public class SpanClosingListener implements AfterServiceListener {
  public static final SpanClosingListener LISTENER = new SpanClosingListener();

  @Override
  public void onAfterService(final Request request) {
    final Object spanAttr = request.getAttribute(DD_SPAN_ATTRIBUTE);
    if (spanAttr instanceof AgentSpan) {
      request.removeAttribute(DD_SPAN_ATTRIBUTE);
      final AgentSpan span = (AgentSpan) spanAttr;
      DECORATE.onResponse(span, request.getResponse());
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
