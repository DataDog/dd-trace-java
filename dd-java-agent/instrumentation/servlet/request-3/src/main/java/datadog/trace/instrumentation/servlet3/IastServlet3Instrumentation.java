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
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class IastServlet3Instrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy {
  public IastServlet3Instrumentation() {
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
        packageName + ".IastServlet3Advice");
  }

  @Override
  protected boolean isOptOutEnabled() {
    return true;
  }
}
