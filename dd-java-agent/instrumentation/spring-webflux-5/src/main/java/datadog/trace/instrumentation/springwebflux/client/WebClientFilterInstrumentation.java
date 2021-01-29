package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class WebClientFilterInstrumentation extends Instrumenter.Tracing {

  public WebClientFilterInstrumentation() {
    super("spring-webflux", "spring-webflux-client");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.springframework.web.reactive.function.client.WebClient");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebfluxHttpClientDecorator",
      packageName + ".TraceWebClientSubscriber",
      packageName + ".WebClientTracingFilter",
      packageName + ".WebClientTracingFilter$MonoWebClientTrace",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.springframework.web.reactive.function.client.DefaultWebClientBuilder");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();

    // This one can't possibly happen on multiple threads so makes sure we are always added to the
    // list initially
    transformers.put(
        isConstructor(), packageName + ".WebClientFilterAdvices$AfterConstructorAdvice");
    // These methods are not thread safe already so doing our work here shouldn't change the
    // likelihood of ConcurrentModificationException happening
    transformers.put(
        isMethod().and(isPublic()).and(named("filter").or(named("filters"))),
        packageName + ".WebClientFilterAdvices$AfterFilterListModificationAdvice");

    return transformers;
  }
}
