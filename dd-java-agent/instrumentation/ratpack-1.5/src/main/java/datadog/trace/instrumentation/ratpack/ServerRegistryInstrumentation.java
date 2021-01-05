package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ServerRegistryInstrumentation extends Instrumenter.Tracing {

  public ServerRegistryInstrumentation() {
    super("ratpack");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("ratpack.server.internal.ServerRegistry");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RatpackServerDecorator",
      packageName + ".RequestURIAdapterAdapter",
      packageName + ".TracingHandler",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(isStatic()).and(named("buildBaseRegistry")),
        packageName + ".ServerRegistryAdvice");
  }
}
