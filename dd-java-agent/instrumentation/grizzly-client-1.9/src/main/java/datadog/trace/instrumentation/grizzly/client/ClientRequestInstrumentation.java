package datadog.trace.instrumentation.grizzly.client;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.Pair;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class ClientRequestInstrumentation extends Instrumenter.Default {

  public ClientRequestInstrumentation() {
    super("grizzly-client", "ning");
  }

  static final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassesNamed("com.ning.http.client.AsyncHandler");

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("com.ning.http.client.AsyncHandler", Pair.class.getName());
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.ning.http.client.AsyncHttpClient");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ClientDecorator", packageName + ".InjectAdapter"};
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("executeRequest")
            .and(takesArgument(0, named("com.ning.http.client.Request")))
            .and(takesArgument(1, named("com.ning.http.client.AsyncHandler")))
            .and(isPublic()),
        packageName + ".ClientRequestAdvice");
  }
}
