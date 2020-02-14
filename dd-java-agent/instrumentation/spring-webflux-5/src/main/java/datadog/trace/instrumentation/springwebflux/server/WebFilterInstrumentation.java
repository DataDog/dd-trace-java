package datadog.trace.instrumentation.springwebflux.server;

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
public class WebFilterInstrumentation extends Instrumenter.Default {
  public WebFilterInstrumentation() {
    super("spring-webflux", "spring-webflux-server");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".FilterCustomizer", packageName + ".TracingWebFilter",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.springframework.web.server.adapter.WebHttpHandlerBuilder");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(isPublic()).and(named("build")), packageName + ".WebFilterAdvice");
  }
}
