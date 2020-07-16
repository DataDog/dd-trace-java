package datadog.trace.instrumentation.grizzly.client;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.context.ContextStoreDef;
import datadog.trace.agent.tooling.context.ContextStoreMapping;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
@ContextStoreDef({
  @ContextStoreMapping(
      keyClass = "com.ning.http.client.AsyncHandler",
      contextClass = "datadog.trace.bootstrap.instrumentation.api.Pair"),
})
public final class ClientRequestInstrumentation extends Instrumenter.Default {

  public ClientRequestInstrumentation() {
    super("grizzly-client", "ning");
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
