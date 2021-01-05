package datadog.trace.instrumentation.servlet2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class Servlet2ResponseStatusInstrumentation extends Instrumenter.Tracing {
  public Servlet2ResponseStatusInstrumentation() {
    super("servlet", "servlet-2");
  }

  // this is required to make sure servlet 2 instrumentation won't apply to servlet 3
  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return Servlet2Instrumentation.CLASS_LOADER_MATCHER;
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return safeHasSuperType(named("javax.servlet.http.HttpServletResponse"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("javax.servlet.ServletResponse", Integer.class.getName());
  }

  /**
   * Unlike Servlet2Instrumentation it doesn't matter if the HttpServletResponseInstrumentation
   * applies first
   */
  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        namedOneOf("sendError", "setStatus"), packageName + ".Servlet2ResponseStatusAdvice");
    transformers.put(named("sendRedirect"), packageName + ".Servlet2ResponseRedirectAdvice");
    return transformers;
  }
}
