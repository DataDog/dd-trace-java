package datadog.trace.instrumentation.servlet2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class IastServlet2Instrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public IastServlet2Instrumentation() {
    super("servlet", "servlet-2");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-2.x";
  }

  // Avoid matching servlet 3 which has its own instrumentation
  static final ElementMatcher.Junction<ClassLoader> NOT_SERVLET_3 =
      not(hasClassNamed("javax.servlet.AsyncEvent"));

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return NOT_SERVLET_3;
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
  public Map<String, String> contextStore() {
    return Collections.singletonMap("javax.servlet.ServletContext", Boolean.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("doFilter", "service")
            .and(takesArgument(0, named("javax.servlet.ServletRequest")))
            .and(takesArgument(1, named("javax.servlet.ServletResponse")))
            .and(isPublic()),
        packageName + ".IastServlet2Advice");
  }

  @Override
  protected boolean isOptOutEnabled() {
    return true;
  }

  @Override
  public int order() {
    // apply this instrumentation after the regular servlet one.
    return 1;
  }
}
