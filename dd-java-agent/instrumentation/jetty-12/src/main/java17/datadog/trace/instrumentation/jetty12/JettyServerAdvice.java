package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.internal.HttpChannelState;

public class JettyServerAdvice {
  public static class HandleAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This final HttpChannelState channel,
        @Advice.Return(readOnly = false) Runnable ret) {
      Request req = channel.getRequest();
      Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (existingSpan instanceof AgentSpan) {
        try (final ContextScope scope = ((AgentSpan) existingSpan).attach()) {
          ret = JettyRunnableWrapper.wrapIfNeeded(ret);
          return;
        }
      }

      final Context context = JettyDecorator.DECORATE.extract(req);
      final AgentSpan span = JettyDecorator.DECORATE.startSpan(req, context);
      try (final ContextScope scope = context.with(span).attach()) {
        span.setMeasured(true);
        JettyDecorator.DECORATE.afterStart(span);
        JettyDecorator.DECORATE.onRequest(span, req, req, context);

        req.setAttribute(DD_SPAN_ATTRIBUTE, span);
        req.setAttribute(CorrelationIdentifier.getTraceIdKey(), GlobalTracer.get().getTraceId());
        req.setAttribute(CorrelationIdentifier.getSpanIdKey(), GlobalTracer.get().getSpanId());
        ret = JettyRunnableWrapper.wrapIfNeeded(ret);
      }
    }
  }

  /**
   * Jetty ensures that connections are reset immediately after the response is sent. This provides
   * a reliable point to finish the server span at the last possible moment.
   */
  public static class ResetAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void stopSpan(@Advice.This final HttpChannelState channel) {
      Request req = channel.getRequest();
      Object spanObj = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (spanObj instanceof AgentSpan) {
        final AgentSpan span = (AgentSpan) spanObj;
        JettyDecorator.DECORATE.onResponse(span, channel);
        JettyDecorator.DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
