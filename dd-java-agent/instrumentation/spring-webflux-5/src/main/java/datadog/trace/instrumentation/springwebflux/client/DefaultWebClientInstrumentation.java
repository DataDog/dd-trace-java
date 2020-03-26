package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class DefaultWebClientInstrumentation extends Instrumenter.Default {

  public DefaultWebClientInstrumentation() {
    super("spring-webflux", "spring-webflux-client");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.springframework.web.reactive.function.client.ExchangeFunction");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return implementsInterface(
        named("org.springframework.web.reactive.function.client.ExchangeFunction"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebfluxHttpClientDecorator",
      packageName + ".HttpHeadersInjectAdapter",
      packageName + ".TracingClientResponseSubscriber",
      packageName + ".TracingClientResponseSubscriber$1",
      packageName + ".TracingClientResponseMono",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(isPublic())
            .and(named("exchange"))
            .and(
                takesArgument(
                    0, named("org.springframework.web.reactive.function.client.ClientRequest"))),
        packageName + ".DefaultWebClientAdvice");
  }
}
