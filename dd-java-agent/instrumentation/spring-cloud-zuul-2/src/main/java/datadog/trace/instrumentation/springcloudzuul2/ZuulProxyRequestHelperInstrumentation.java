package datadog.trace.instrumentation.springcloudzuul2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.springcloudzuul2.ResourceNameCache.RESOURCE_NAME_CACHE;
import static datadog.trace.instrumentation.springcloudzuul2.ResourceNameCache.RESOURCE_NAME_JOINER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

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
public class ZuulProxyRequestHelperInstrumentation extends Instrumenter.Tracing {
  public ZuulProxyRequestHelperInstrumentation() {
    super("spring-cloud-zuul");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.springframework.cloud.netflix.zuul.filters.ProxyRequestHelper");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
//      packageName + ".ResourceNameCache", packageName + ".ResourceNameCache$1",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
      isMethod().and(named("isIncludedHeader")).and(takesArgument(0, TypeDescription.STRING)).and(returns(Boolean.class)),
      ZuulProxyRequestHelperInstrumentation.class.getName() + "$ExcludeDDHeaderAdvice");
  }

  /**
   * Using the zuul proxy results in the Spring "HandlerMapping.bestMatchingPattern" value being
   * very generic. In the case where zuul forwards the request to a more specific Spring controller,
   * a better pattern will be updated on the request after the call returns, so we want to update
   * the resource name with that.
   */
  public static class ExcludeDDHeaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Argument(0) final String header, @Advice.Return(readOnly = false) boolean include) {
      if () {
      }
    }
  }
}
