package datadog.trace.instrumentation.springcloudzuul2;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.springcloudzuul2.ResourceNameCache.RESOURCE_NAME_CACHE;
import static datadog.trace.instrumentation.springcloudzuul2.ResourceNameCache.RESOURCE_NAME_JOINER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import com.netflix.zuul.context.RequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Pair;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ZuulSendForwardFilterInstrumentation extends Instrumenter.Tracing {
  public ZuulSendForwardFilterInstrumentation() {
    super("spring-cloud-zuul");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.springframework.cloud.netflix.zuul.filters.route.SendForwardFilter");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ResourceNameCache", packageName + ".ResourceNameCache$1",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {

    return singletonMap(
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
        Object span = request.getAttribute(DD_SPAN_ATTRIBUTE);
        if (span instanceof AgentSpan) {
          parentSpan = (AgentSpan) span;
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
          final CharSequence resourceName =
              RESOURCE_NAME_CACHE.computeIfAbsent(
                  Pair.of(method, bestMatchingPattern), RESOURCE_NAME_JOINER);
          parentSpan.setResourceName(resourceName);
        }
      }
    }
  }
}
