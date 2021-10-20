package datadog.trace.instrumentation.http4s021_212;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class Http4sHttpServerInstrumentation extends Instrumenter.Tracing {
  public Http4sHttpServerInstrumentation() {
    super("http4s-http", "http4s-http-server");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.http4s.server.blaze.BlazeServerBuilder");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("withHttpApp")), packageName + ".Http4sServerBuilderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".Http4sHttpServerDecorator",
      packageName + ".Http4sURIAdapter",
      packageName + ".Http4sServerBuilderAdvice",
      packageName + ".ServerWrapper",
      packageName + ".ServerWrapper$",
    };
  }
}
