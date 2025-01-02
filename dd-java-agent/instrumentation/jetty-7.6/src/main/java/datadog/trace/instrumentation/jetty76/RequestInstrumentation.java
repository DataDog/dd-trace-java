package datadog.trace.instrumentation.jetty76;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_PATH;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty76.JettyDecorator.DD_CONTEXT_PATH_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty76.JettyDecorator.DD_SERVLET_PATH_ATTRIBUTE;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Request;

@AutoService(InstrumenterModule.class)
public final class RequestInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public RequestInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Request";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("setContextPath").and(takesArgument(0, String.class)),
        RequestInstrumentation.class.getName() + "$SetContextPathAdvice");
    transformer.applyAdvice(
        named("setServletPath").and(takesArgument(0, String.class)),
        RequestInstrumentation.class.getName() + "$SetServletPathAdvice");
    transformer.applyAdvice(
        named("setRequestedSessionId").and(takesArgument(0, String.class)),
        RequestInstrumentation.class.getName() + "$SetRequestedSessionId");
  }

  /**
   * Because we are processing the initial request before the contextPath is set, we must update it
   * when it is actually set.
   */
  public static class SetContextPathAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void updateContextPath(
        @Advice.This final Request req, @Advice.Argument(0) final String contextPath) {
      if (contextPath != null) {
        Object span = req.getAttribute(DD_SPAN_ATTRIBUTE);
        // Don't want to update while being dispatched to new servlet
        if (span instanceof AgentSpan && req.getAttribute(DD_DISPATCH_SPAN_ATTRIBUTE) == null) {
          ((AgentSpan) span).setTag(SERVLET_CONTEXT, contextPath);
          req.setAttribute(DD_CONTEXT_PATH_ATTRIBUTE, contextPath);
        }
      }
    }
  }

  /**
   * Because we are processing the initial request before the servletPath is set, we must update it
   * when it is actually set.
   */
  public static class SetServletPathAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void updateServletPath(
        @Advice.This final Request req, @Advice.Argument(0) final String servletPath) {
      if (servletPath != null && !servletPath.isEmpty()) { // bypass cleanup
        Object span = req.getAttribute(DD_SPAN_ATTRIBUTE);
        // Don't want to update while being dispatched to new servlet
        if (span instanceof AgentSpan && req.getAttribute(DD_DISPATCH_SPAN_ATTRIBUTE) == null) {
          ((AgentSpan) span).setTag(SERVLET_PATH, servletPath);
          req.setAttribute(DD_SERVLET_PATH_ATTRIBUTE, servletPath);
        }
      }
    }

    private void muzzleCheck(AbstractHttpConnection connection) {
      connection.getGenerator();
    }
  }

  /**
   * Because we are processing the initial request before the requestedSessionId is set, we must
   * update it when it is actually set.
   */
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class SetRequestedSessionId {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void updateContextPath(
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Argument(0) final String requestedSessionId) {
      if (requestedSessionId != null && reqCtx != null) {
        final CallbackProvider cbp =
            AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
        if (cbp == null) {
          return;
        }
        final BiFunction<RequestContext, String, Flow<Void>> addrCallback =
            cbp.getCallback(EVENTS.requestSession());
        if (addrCallback == null) {
          return;
        }
        final Flow<Void> flow = addrCallback.apply(reqCtx, requestedSessionId);
        Flow.Action action = flow.getAction();
        if (action instanceof Flow.Action.RequestBlockingAction) {
          throw new BlockingException("Blocked request (for sessionId)");
        }
      }
    }
  }
}
