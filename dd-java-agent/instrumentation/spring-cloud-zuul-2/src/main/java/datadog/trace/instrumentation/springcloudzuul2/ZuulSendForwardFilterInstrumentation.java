package datadog.trace.instrumentation.springcloudzuul2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import com.netflix.zuul.context.RequestContext;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ZuulSendForwardFilterInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ZuulSendForwardFilterInstrumentation() {
    super("spring-cloud-zuul");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.cloud.netflix.zuul.filters.route.SendForwardFilter";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("run")).and(takesNoArguments()),
        ZuulSendForwardFilterInstrumentation.class.getName() + "$FilterInjectingAdvice");
  }

  /**
   * Using the zuul proxy results in the Spring "HandlerMapping.bestMatchingPattern" value being
   * very generic. In the case where zuul forwards the request to a more specific Spring controller,
   * a better pattern will be updated on the request after the call returns, so we want to update
   * the resource name with that.
   */
  public static class FilterInjectingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Local("request") HttpServletRequest request,
        @Advice.Local("parentSpan") AgentSpan parentSpan) {
      RequestContext ctx = RequestContext.getCurrentContext();
      request = ctx.getRequest();
      if (request != null) {
        // Capture the span from the request before forwarding.
        Object contextObj = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
        if (contextObj instanceof Context) {
          Context context = (Context) contextObj;
          parentSpan = spanFromContext(context);
        }
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Local("request") HttpServletRequest request,
        @Advice.Local("parentSpan") AgentSpan parentSpan) {
      if (request != null && parentSpan != null) {
        final String method = request.getMethod();
        // Get the updated route pattern.
        // Opted for static string here to avoid an additional spring dependency.
        final Object bestMatchingPattern =
            request.getAttribute(
                "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
        if (method != null && bestMatchingPattern != null) {
          HTTP_RESOURCE_DECORATOR.withRoute(parentSpan, method, bestMatchingPattern.toString());
        }
      }
    }
  }
}
