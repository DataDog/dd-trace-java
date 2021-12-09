package datadog.trace.instrumentation.http4s023_212;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Arrays;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class Http4sHttpServerInstrumentation extends Instrumenter.Tracing {
  private static final String[] ALL_INSTRUMENTATION_NAMES = {
    "experimental.http4s-blaze-server",
    "experimental.http4s-server",
    "experimental.http4s",
    "experimental"
  };

  public Http4sHttpServerInstrumentation() {
    super(
        ALL_INSTRUMENTATION_NAMES[0],
        Arrays.copyOfRange(ALL_INSTRUMENTATION_NAMES, 1, ALL_INSTRUMENTATION_NAMES.length));
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.http4s.blaze.server.BlazeServerBuilder");
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
      packageName + ".Http4sHttpServerDecorator$",
      packageName + ".Http4sServerHeaders",
      packageName + ".Http4sServerHeaders$",
      packageName + ".Http4sURIAdapter",
      packageName + ".ServerWrapper",
      packageName + ".ServerWrapper$",
    };
  }
}
