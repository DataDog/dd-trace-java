package datadog.trace.instrumentation.springcloudzuul2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static datadog.trace.instrumentation.springcloudzuul2.HeaderUtils.EXCLUDED_HEADERS;
import static datadog.trace.instrumentation.springcloudzuul2.HeaderUtils.DD_PACKAGE_PREFIX;
import static datadog.trace.instrumentation.springcloudzuul2.HeaderUtils.HAYSTACK_PACKAGE_PREFIX;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
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
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(named("isIncludedHeader")).and(takesArgument(0, TypeDescription.STRING)),
        ZuulProxyRequestHelperInstrumentation.class.getName() + "$ProxyRequestHelperAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HeaderUtils"
    };
  }

  /**
   * The purpose of this instrumentation is to prevent the overwriting of instrumented headers when
   * a request is passed through a Zuul proxy. This instrumentation will ensure that the proxy's
   * action of filtering/forwarding the incoming request will not overwrite the headers added by the
   * agent when instrumenting the proxy
   */
  public static class ProxyRequestHelperAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) final String header, @Advice.Return(readOnly = false) boolean include) {

      if (EXCLUDED_HEADERS.contains(header)
          || DD_PACKAGE_PREFIX.startsWith(header)
          || HAYSTACK_PACKAGE_PREFIX.startsWith(header)) include = false;
    }
  }
}
