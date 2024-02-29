package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static java.util.stream.Collectors.*;
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
import java.util.Collections;
import java.util.Map;
import javax.servlet.ServletContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class IastServlet3ContextInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy {

  private static final String TYPE = "javax.servlet.ServletContext";

  public IastServlet3ContextInstrumentation() {
    super("servlet", "servlet-3", "servlet-context");
  }

  @Override
  public String hierarchyMarkerType() {
    return TYPE;
  }

  @Override
  public String muzzleDirective() {
    return "servlet-3.x";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(namedNoneOf("org.apache.catalina.core.ApplicationContext")); // Tomcat has
    // org.apache.catalina.core.ApplicationContextFacade which implements ServletContext and calls
    // internally org.apache.catalina.core.ApplicationContext
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(TYPE, String.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("getRealPath")).and(takesArguments(String.class)),
        IastServlet3ContextInstrumentation.class.getName() + "$IastContextAdvice");
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
