package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class ServerRegistryInstrumentation extends Instrumenter.Tracing {

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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isStatic()).and(named("buildBaseRegistry")),
        packageName + ".ServerRegistryAdvice");
  }
}
