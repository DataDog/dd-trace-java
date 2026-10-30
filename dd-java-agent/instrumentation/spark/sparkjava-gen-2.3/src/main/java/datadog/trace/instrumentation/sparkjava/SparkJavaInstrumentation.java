package datadog.trace.instrumentation.sparkjava;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import spark.route.HttpMethod;
import spark.routematch.RouteMatch;

/**
 * SparkJava route-enrichment instrumentation. SparkJava runs on an embedded Jetty server whose
 * instrumentation already creates the HTTP server span with all required tags (http.method,
 * http.url, http.status_code, component, etc.) and handles context propagation. This
 * instrumentation enriches the active Jetty span with the matched SparkJava route pattern
 * (http.route) by hooking Routes.find(), which is the method that resolves an incoming request to a
 * registered route.
 *
 * <p>Note: the spec identified MatcherFilter.doFilter as the instrumentation target, but creating a
 * second server span there would produce duplicate spans. Instead, we follow the established
 * route-enrichment pattern used by similar frameworks (Ratpack, Finatra) that also run on top of
 * another HTTP server.
 */
@AutoService(InstrumenterModule.class)
public class SparkJavaInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SparkJavaInstrumentation() {
    super("sparkjava", "sparkjava-2.4");
  }

  @Override
  public String instrumentedType() {
    return "spark.route.Routes";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("find")
            .and(takesArgument(0, named("spark.route.HttpMethod")))
            .and(returns(named("spark.routematch.RouteMatch")))
            .and(isPublic()),
        SparkJavaInstrumentation.class.getName() + "$RouteMatchAdvice");
  }

  public static class RouteMatchAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) final HttpMethod method, @Advice.Return final RouteMatch routeMatch) {
      final AgentSpan span = activeSpan();
      if (span != null && routeMatch != null) {
        HTTP_RESOURCE_DECORATOR.withRoute(span, method.name(), routeMatch.getMatchUri());
      }
    }
  }
}
