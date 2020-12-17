package datadog.trace.instrumentation.axway;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class AxwayHTTPPluginInstrumentation extends Instrumenter.Tracing {

  public AxwayHTTPPluginInstrumentation() {
    super("axway-api");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf("com.vordel.dwe.http.HTTPPlugin", "com.vordel.circuit.net.State");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".StateAdvice",
      packageName + ".AxwayHTTPPluginDecorator",
      packageName + ".HTTPPluginAdvice",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod().and(isPublic()).and(named("invoke")), packageName + ".HTTPPluginAdvice");
    transformers.put(
        isMethod().and(isPublic()).and(named("tryTransaction")), packageName + ".StateAdvice");
    System.out.println("AxwayHTTPPluginInstrumentation : " + transformers);
    return transformers;
  }
}
