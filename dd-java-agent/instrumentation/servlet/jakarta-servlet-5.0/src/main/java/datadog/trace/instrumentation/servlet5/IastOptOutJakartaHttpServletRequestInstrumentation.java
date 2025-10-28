package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.*;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.bootstrap.InstrumentationContext;
import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.*;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class IastOptOutJakartaHttpServletRequestInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final String CLASS_NAME =
      IastOptOutJakartaHttpServletRequestInstrumentation.class.getName();
  private static final ElementMatcher.Junction<? super TypeDescription> WRAPPER_CLASS =
      named("jakarta.servlet.http.HttpServletRequestWrapper");

  public IastOptOutJakartaHttpServletRequestInstrumentation() {
    super("servlet", "servlet-5", "servlet-request");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.HttpServletRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(not(WRAPPER_CLASS))
        .and(not(extendsClass(WRAPPER_CLASS)));
  }

  @Override
  protected boolean isOptOutEnabled() {
    return true;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getSession").and(returns(named("jakarta.servlet.http.HttpSession"))).and(isPublic()),
        CLASS_NAME + "$GetHttpSessionAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "jakarta.servlet.ServletContext", "jakarta.servlet.SessionTrackingMode");
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
