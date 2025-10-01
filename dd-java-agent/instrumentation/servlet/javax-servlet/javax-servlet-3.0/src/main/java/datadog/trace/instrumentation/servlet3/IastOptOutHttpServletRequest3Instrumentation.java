package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletContext;
import javax.servlet.SessionTrackingMode;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class IastOptOutHttpServletRequest3Instrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public IastOptOutHttpServletRequest3Instrumentation() {
    super("servlet", "servlet-3");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-3.x";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Avoid matching request before servlet-3.x which don't have session tracking modes
    return hasClassNamed("javax.servlet.SessionTrackingMode");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        // ignore wrappers that ship with servlet-api
        .and(namedNoneOf("javax.servlet.http.HttpServletRequestWrapper"))
        .and(not(extendsClass(named("javax.servlet.http.HttpServletRequestWrapper"))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getSession").and(returns(named("javax.servlet.http.HttpSession"))).and(isPublic()),
        getClass().getName() + "$GetHttpSessionAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "javax.servlet.ServletContext", "javax.servlet.SessionTrackingMode");
  }

  @Override
  protected boolean isOptOutEnabled() {
    return true;
  }

  public static class GetHttpSessionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.SESSION_REWRITING)
    public static void onExit(
        @Advice.This final HttpServletRequest request, @Advice.Return final HttpSession session) {
      if (session == null) {
        return;
      }
      final ApplicationModule module = InstrumentationBridge.APPLICATION;
      if (module == null) {
        return;
      }
      final ServletContext context = request.getServletContext();
      if (InstrumentationContext.get(ServletContext.class, SessionTrackingMode.class).get(context)
          != null) {
        return;
      }
      // We only want to report it once per application
      InstrumentationContext.get(ServletContext.class, SessionTrackingMode.class)
          .put(context, SessionTrackingMode.URL);
      if (context.getEffectiveSessionTrackingModes() != null
          && !context.getEffectiveSessionTrackingModes().isEmpty()) {
        Set<String> sessionTrackingModes = new HashSet<>();
        for (SessionTrackingMode mode : context.getEffectiveSessionTrackingModes()) {
          sessionTrackingModes.add(mode.name());
        }
        module.checkSessionTrackingModes(sessionTrackingModes);
      }
    }
  }

  @Override
  public int order() {
    // apply this instrumentation after the regular servlet one.
    return 1;
  }
}
