package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.api.iast.sink.SessionRewritingModule;
import datadog.trace.bootstrap.InstrumentationContext;
import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServlet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class IastJakartaServletInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy {
  public IastJakartaServletInstrumentation() {
    super("servlet", "servlet-5");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.HttpServlet";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasSuperType(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("jakarta.servlet.ServletContext", Boolean.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("service"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, named("jakarta.servlet.ServletRequest")))
            .and(takesArgument(1, named("jakarta.servlet.ServletResponse"))),
        getClass().getName() + "$IastAdvice");
  }

  public static class IastAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(@Advice.This final HttpServlet servlet) {
      final ApplicationModule applicationModule = InstrumentationBridge.APPLICATION;
      final SessionRewritingModule sessionRewritingModule = InstrumentationBridge.SESSION_REWRITING;
      if (applicationModule == null && sessionRewritingModule == null) {
        return;
      }
      final ServletContext context = servlet.getServletContext();
      if (InstrumentationContext.get(ServletContext.class, Boolean.class).get(context) != null) {
        return;
      }
      InstrumentationContext.get(ServletContext.class, Boolean.class).put(context, true);
      if (applicationModule != null) {
        applicationModule.onRealPath(context.getRealPath("/"));
      }
      if (sessionRewritingModule != null
          && context.getEffectiveSessionTrackingModes() != null
          && !context.getEffectiveSessionTrackingModes().isEmpty()) {
        Set<String> sessionTrackingModes = new HashSet<>();
        for (SessionTrackingMode mode : context.getEffectiveSessionTrackingModes()) {
          sessionTrackingModes.add(mode.name());
        }
        sessionRewritingModule.checkSessionTrackingModes(sessionTrackingModes);
      }
    }
  }
}
