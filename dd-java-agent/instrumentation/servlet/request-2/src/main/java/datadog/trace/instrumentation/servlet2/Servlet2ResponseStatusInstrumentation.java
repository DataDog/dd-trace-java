package datadog.trace.instrumentation.servlet2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class Servlet2ResponseStatusInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public Servlet2ResponseStatusInstrumentation() {
    super("servlet", "servlet-2");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-2.x";
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return Servlet2Instrumentation.NOT_SERVLET_3;
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        namedOneOf("sendError", "setStatus").and(takesArgument(0, int.class)),
        packageName + ".Servlet2ResponseStatusAdvice");
    transformation.applyAdvice(
        named("sendRedirect"), packageName + ".Servlet2ResponseRedirectAdvice");
  }
}
