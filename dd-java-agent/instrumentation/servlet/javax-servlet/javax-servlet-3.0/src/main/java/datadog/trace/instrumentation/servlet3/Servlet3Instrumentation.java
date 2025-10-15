package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class Servlet3Instrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public Servlet3Instrumentation() {
    super("servlet", "servlet-3");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-3.x";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Avoid matching servlet 2 which has its own instrumentation
    return hasClassNamed("javax.servlet.AsyncEvent");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServlet";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("javax.servlet.http.HttpServlet"))
        .or(implementsInterface(named("javax.servlet.FilterChain")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HttpServletExtractAdapter",
      packageName + ".HttpServletExtractAdapter$Request",
      packageName + ".HttpServletExtractAdapter$Response",
      packageName + ".Servlet3Decorator",
      packageName + ".ServletRequestURIAdapter",
      packageName + ".FinishAsyncDispatchListener",
      packageName + ".RumHttpServletRequestWrapper",
      packageName + ".RumHttpServletResponseWrapper",
      packageName + ".WrappedServletOutputStream",
      "datadog.trace.instrumentation.servlet.ServletBlockingHelper",
    };
  }

  /**
   * Here we are instrumenting the public method for HttpServlet. This should ensure that this
   * advice is always called before HttpServletInstrumentation which is instrumenting the protected
   * method.
   */
  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("doFilter", "service")
            .and(takesArgument(0, named("javax.servlet.ServletRequest")))
            .and(takesArgument(1, named("javax.servlet.ServletResponse")))
            .and(isPublic()),
        packageName + ".Servlet3Advice");
  }
}
