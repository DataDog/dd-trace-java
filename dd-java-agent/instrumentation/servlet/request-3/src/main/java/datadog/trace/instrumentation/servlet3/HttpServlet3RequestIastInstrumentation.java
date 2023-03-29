package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.source.WebModule;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * IAST instrumentation for Servlet 3 and above. Methods that are available since Servlet 2 are
 * instrumented in the "servlet" module.
 */
@AutoService(Instrumenter.class)
public final class HttpServlet3RequestIastInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {
  public HttpServlet3RequestIastInstrumentation() {
    super("servlet", "servlet-request", "servlet-3");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-3.x";
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Avoid matching servlet 2.
    return hasClassNamed("javax.servlet.AsyncEvent");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // Instrumenting wrappers is, most likely, redundant.
        .and(not(extendsClass(named("javax.servlet.http.HttpServletRequestWrapper"))));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getParameterMap").and(takesArguments(0)),
        HttpServlet3RequestIastInstrumentation.class.getName() + "$GetParameterMapAdvice");
  }

  @IastAdvice.Source(SourceTypes.REQUEST_PARAMETER_VALUE_STRING)
  public static class GetParameterMapAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getParameterMap(@Advice.Return final Map<String, String[]> value) {
      final WebModule module = InstrumentationBridge.WEB;
      if (module != null) {
        module.onParameterValues(value);
      }
    }
  }
}
