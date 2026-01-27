package datadog.trace.instrumentation.jetty12;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_PARENT_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.internal.HttpChannelState;

public class JettyServerAdvice {

  @AppliesOn(CONTEXT_TRACKING)
  public static class ContextTrackingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope enter(@Advice.This final HttpChannel httpChannel) {
      final Request request = httpChannel.getRequest();
      final Object contextObj = request.getAttribute(DD_PARENT_CONTEXT_ATTRIBUTE);
      if (contextObj instanceof Context) {
        return ((Context) contextObj).attach();
      }
      final Context parent = DECORATE.extract(request);
      request.setAttribute(DD_PARENT_CONTEXT_ATTRIBUTE, parent);
      return parent.attach();
    }

    public static void exit(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  public static class HandleAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This final HttpChannelState channel,
        @Advice.Return(readOnly = false) Runnable ret) {
      Request req = channel.getRequest();
      Object existingContext = req.getAttribute(DD_CONTEXT_ATTRIBUTE);
      if (existingContext instanceof Context) {
        try (final ContextScope scope = ((Context) existingContext).attach()) {
          ret = JettyRunnableWrapper.wrapIfNeeded(ret);
          return;
        }
      }

      final Object parentContextObj = req.getAttribute(DD_PARENT_CONTEXT_ATTRIBUTE);
      final Context parentContext =
          (parentContextObj instanceof Context) ? (Context) parentContextObj : null;
      final Context context = DECORATE.startSpan(req, parentContext);
      try (final ContextScope ignored = context.attach()) {
        final AgentSpan span = fromContext(context);
        span.setMeasured(true);
        DECORATE.afterStart(span);
        DECORATE.onRequest(span, req, req, parentContext);

        req.setAttribute(DD_CONTEXT_ATTRIBUTE, context);
        req.setAttribute(CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
        req.setAttribute(CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());
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
      Object contextObj = req.getAttribute(DD_CONTEXT_ATTRIBUTE);
      if (contextObj instanceof Context) {
        final Context context = (Context) contextObj;
        final AgentSpan span = fromContext(context);
        if (span != null) {
          DECORATE.onResponse(span, channel);
          DECORATE.beforeFinish(context);
          span.finish();
        }
      }
    }
  }
}
