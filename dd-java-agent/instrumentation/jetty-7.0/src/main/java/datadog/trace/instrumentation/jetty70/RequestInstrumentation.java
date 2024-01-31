package datadog.trace.instrumentation.jetty70;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_PATH;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty70.JettyDecorator.DD_CONTEXT_PATH_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty70.JettyDecorator.DD_SERVLET_PATH_ATTRIBUTE;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;

@AutoService(Instrumenter.class)
public final class RequestInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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

    private void muzzleCheck(HttpConnection connection) {
      connection.getGenerator();
    }
  }
}
