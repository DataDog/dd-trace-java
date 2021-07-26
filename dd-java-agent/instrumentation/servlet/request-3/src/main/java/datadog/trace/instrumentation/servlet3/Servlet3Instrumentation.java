package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class Servlet3Instrumentation extends Instrumenter.Tracing {
  public Servlet3Instrumentation() {
    super("servlet", "servlet-3");
  }

  private final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassesNamed("javax.servlet.http.HttpServletResponse");

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return safeHasSuperType(
        namedOneOf("javax.servlet.FilterChain", "javax.servlet.http.HttpServlet"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.servlet.http.common.ServletHttpServerDecorator",
      packageName + ".Servlet3Decorator",
      packageName + ".ServletRequestURIAdapter",
      packageName + ".HttpServletRequestExtractAdapter",
      packageName + ".TagSettingAsyncListener",
      "datadog.trace.instrumentation.servlet.http.common.BodyCapturingHttpServletRequest",
      "datadog.trace.instrumentation.servlet.http.common.BodyCapturingHttpServletRequest$ServletInputStreamWrapper",
      "datadog.trace.instrumentation.servlet.http.common.BodyCapturingHttpServletRequest$BufferedReaderWrapper",
      "datadog.trace.instrumentation.servlet.http.common.IGDelegatingStoredBodyListener",
      "datadog.trace.instrumentation.servlet.http.common.IGDelegatingStoredBodyListener$1",
      "datadog.trace.instrumentation.servlet.http.common.IGDelegatingStoredBodyListener$2",
    };
  }

  /**
   * Here we are instrumenting the public method for HttpServlet. This should ensure that this
   * advice is always called before HttpServletInstrumentation which is instrumenting the protected
   * method.
   */
  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        namedOneOf("doFilter", "service")
            .and(takesArgument(0, named("javax.servlet.ServletRequest")))
            .and(takesArgument(1, named("javax.servlet.ServletResponse")))
            .and(isPublic()),
        packageName + ".Servlet3Advice");
  }
}
