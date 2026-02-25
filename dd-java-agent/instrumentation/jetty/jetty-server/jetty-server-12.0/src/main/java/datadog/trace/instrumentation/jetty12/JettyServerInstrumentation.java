package datadog.trace.instrumentation.jetty12;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.internal.HttpChannelState;

final class JettyServerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.internal.HttpChannelState";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("onRequest").and(takesArguments(1)),
        JettyServerInstrumentation.class.getName() + "$HandleAdvice");
    transformer.applyAdvice(
        named("recycle").and(takesNoArguments()),
        JettyServerInstrumentation.class.getName() + "$ResetAdvice");
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

      final Context parentContext = DECORATE.extract(req);
      final Context context = DECORATE.startSpan(req, parentContext);
      try (final ContextScope ignored = context.attach()) {
        final AgentSpan span = AgentSpan.fromContext(context);
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
        final AgentSpan span = AgentSpan.fromContext(context);
        if (span != null) {
          DECORATE.onResponse(span, channel);
          DECORATE.beforeFinish(context);
          span.finish();
        }
        req.removeAttribute(DD_CONTEXT_ATTRIBUTE);
      }
    }
  }
}
