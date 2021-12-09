package datadog.trace.instrumentation.http4s023_212;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Arrays;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Http4sHttpClientInstrumentation extends Instrumenter.Tracing {
  private static final String[] ALL_INSTRUMENTATION_NAMES = {
    "experimental.http4s-blaze-client",
    "experimental.http4s-client",
    "experimental.http4s",
    "experimental"
  };

  public Http4sHttpClientInstrumentation() {
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
    return named("org.http4s.blaze.client.BlazeClientBuilder");
  }

  @Override
  public void adviceTransformations(Instrumenter.AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("resource")), packageName + ".Http4sClientBuilderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".Http4sClientHeaders",
      packageName + ".Http4sClientHeaders$",
      packageName + ".Http4sHttpClientDecorator",
      packageName + ".Http4sHttpClientDecorator$",
      packageName + ".ClientWrapper",
      packageName + ".ClientWrapper$",
    };
  }
}
