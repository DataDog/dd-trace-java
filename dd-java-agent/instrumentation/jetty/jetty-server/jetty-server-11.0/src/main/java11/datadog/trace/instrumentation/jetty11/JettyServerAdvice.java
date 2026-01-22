package datadog.trace.instrumentation.jetty11;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_PARENT_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty11.JettyDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

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

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.This final HttpChannel channel, @Advice.Local("agentSpan") AgentSpan span) {
      Request req = channel.getRequest();

      Object existingContext = req.getAttribute(DD_CONTEXT_ATTRIBUTE);
      if (existingContext instanceof Context) {
        return ((Context) existingContext).attach();
      }

      final Object parentContextObj = req.getAttribute(DD_PARENT_CONTEXT_ATTRIBUTE);
      final Context parentContext = (parentContextObj instanceof Context) ? (Context) parentContextObj : null;
      final Context context = DECORATE.startSpan(req, parentContext);
      final ContextScope scope = context.attach();
      span = fromContext(context);
      span.setMeasured(true);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, req, req, parentContext);

      req.setAttribute(DD_CONTEXT_ATTRIBUTE, context);
      req.setAttribute(CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
      req.setAttribute(CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void closeScope(@Advice.Enter final ContextScope scope) {
      scope.close();
    }

    private void muzzleCheck(Request r) {
      r.getAsyncContext(); // there must be a getAsyncContext returning a jakarta AsyncContext
    }
  }

  /**
   * Jetty ensures that connections are reset immediately after the response is sent. This provides
   * a reliable point to finish the server span at the last possible moment.
   */
  public static class ResetAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void stopSpan(@Advice.This final HttpChannel channel) {
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

    private void muzzleCheck(HttpChannel connection) {
      connection.run();
    }
  }
}
