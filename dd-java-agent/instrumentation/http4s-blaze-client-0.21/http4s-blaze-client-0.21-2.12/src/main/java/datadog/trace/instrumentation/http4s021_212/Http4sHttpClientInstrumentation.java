package datadog.trace.instrumentation.http4s021_212;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class Http4sHttpClientInstrumentation extends Instrumenter.Tracing {

  public Http4sHttpClientInstrumentation() {
    super("http4s-http", "http4s-http-client");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.http4s.client.blaze.BlazeClientBuilder");
  }

  @Override
  public void adviceTransformations(Instrumenter.AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("resource")), packageName + ".Http4sClientBuilderAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".Http4sHttpClientDecorator",
      packageName + ".Http4sClientBuilderAdvice",
      packageName + ".ClientWrapper",
      packageName + ".ClientWrapper$",
    };
  }
}
