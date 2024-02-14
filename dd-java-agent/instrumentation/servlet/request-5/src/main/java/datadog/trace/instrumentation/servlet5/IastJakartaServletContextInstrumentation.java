package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.stream.Collectors.toSet;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.ApplicationModule;
import datadog.trace.api.iast.sink.SessionRewritingModule;
import datadog.trace.bootstrap.InstrumentationContext;
import jakarta.servlet.ServletContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class IastJakartaServletContextInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy {

  private static final String TYPE = "jakarta.servlet.ServletContext";

  public IastJakartaServletContextInstrumentation() {
    super("servlet", "servlet-5", "servlet-context");
  }

  @Override
  public String hierarchyMarkerType() {
    return TYPE;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(TYPE, String.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("getRealPath")).and(takesArguments(String.class)),
        IastJakartaServletContextInstrumentation.class.getName() + "$IastContextAdvice");
  }

  public static class IastContextAdvice {
    @Sink(VulnerabilityTypes.APPLICATION)
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getRealPath(
        @Advice.This final ServletContext context, @Advice.Return final String realPath) {
      final ApplicationModule applicationModule = InstrumentationBridge.APPLICATION;
      final SessionRewritingModule sessionRewritingModule = InstrumentationBridge.SESSION_REWRITING;
      if ((applicationModule != null || sessionRewritingModule != null)
          && InstrumentationContext.get(ServletContext.class, String.class).get(context) == null) {
        InstrumentationContext.get(ServletContext.class, String.class).put(context, realPath);
        if (applicationModule != null) {
          applicationModule.onRealPath(realPath);
        }
        if (sessionRewritingModule != null && context.getEffectiveSessionTrackingModes() != null) {
          sessionRewritingModule.checkSessionTrackingModes(
              context.getEffectiveSessionTrackingModes().stream().map(Enum::name).collect(toSet()));
        }
      }
    }
  }
}
