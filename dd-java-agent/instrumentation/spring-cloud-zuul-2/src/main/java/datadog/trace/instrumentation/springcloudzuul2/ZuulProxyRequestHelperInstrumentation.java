package datadog.trace.instrumentation.springcloudzuul2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
        ZuulProxyRequestHelperInstrumentation.class.getName() + "$ExcludeDDHeaderAdvice");
  }

  /**
   * The purpose of this instrumentation is to prevent the overwriting of instrumented headers when
   * a request is passed through a Zuul proxy. This instrumentation will ensure that the proxy's
   * action of filtering/forwarding the incoming request will not overwrite the headers added by the
   * agent when instrumenting the proxy
   */
  public static class ExcludeDDHeaderAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) final String header, @Advice.Return(readOnly = false) boolean include) {
      // for now get all the B3, Haystack, Datadog headers and ignore them
      Set<String> headers =
          new HashSet<String>(
              Arrays.asList(
                  // DD headers
                  "x-datadog-trace-id",
                  "x-datadog-parent-id",
                  "x-datadog-sampling-priority",
                  "x-datadog-origin",
                  // B3 headers
                  "X-B3-TraceId",
                  "X-B3-SpanId",
                  "X-B3-Sampled",
                  // Haystack headers
                  "Trace-ID",
                  "Span-ID",
                  "Parent-ID",
                  "Haystack-Trace-ID",
                  "Haystack-Span-ID",
                  "Haystack-Parent-ID"));

      String haystack_baggage_prefix = "Baggage-";
      String dd_baggage_prefix = "ot-baggage-";
      if (headers.contains(header)
          || dd_baggage_prefix.startsWith(header)
          || haystack_baggage_prefix.startsWith(header)) include = false;
    }
  }
}
