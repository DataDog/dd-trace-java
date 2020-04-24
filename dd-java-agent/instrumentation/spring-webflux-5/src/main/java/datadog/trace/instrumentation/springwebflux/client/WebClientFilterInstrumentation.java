package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class WebClientFilterInstrumentation extends Instrumenter.Default {

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
      packageName + ".HttpHeadersInjectAdapter",
      packageName + ".WebClientTracingFilter",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return extendsClass(
        named("org.springframework.web.reactive.function.client.WebClient$Builder"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(isPublic()).and(named("build")), packageName + ".WebClientFilterAdvice");
  }
}
