package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static java.util.Collections.singletonMap;
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
public final class Servlet3Instrumentation extends Instrumenter.Default {
  public Servlet3Instrumentation() {
    super("servlet", "servlet-3");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("javax.servlet.http.HttpServlet");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return safeHasSuperType(
        named("javax.servlet.FilterChain").or(named("javax.servlet.http.HttpServlet")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".Servlet3Decorator",
      packageName + ".HttpServletRequestExtractAdapter",
      packageName + ".TagSettingAsyncListener"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "javax.servlet.http.HttpServletResponse", "javax.servlet.http.HttpServletRequest");
  }

  /**
   * Here we are instrumenting the public method for HttpServlet. This should ensure that this
   * advice is always called before HttpServletInstrumentation which is instrumenting the protected
   * method.
   */
  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("doFilter")
            .or(named("service"))
            .and(takesArgument(0, named("javax.servlet.ServletRequest")))
            .and(takesArgument(1, named("javax.servlet.ServletResponse")))
            .and(isPublic()),
        packageName + ".Servlet3Advice");
  }
}
