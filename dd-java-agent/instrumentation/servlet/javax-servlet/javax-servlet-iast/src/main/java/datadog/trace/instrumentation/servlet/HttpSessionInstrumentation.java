package datadog.trace.instrumentation.servlet;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.TrustBoundaryViolationModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class HttpSessionInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public HttpSessionInstrumentation() {
    super("servlet", "servlet-session");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-common";
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpSession";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(
            not(
                namedOneOf(
                    "com.ibm.ws.session.HttpSessionFacade",
                    "org.apache.catalina.session.StandardSessionFacade")));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("setAttribute", "putValue")
            .and(takesArguments(String.class, Object.class).and(isPublic())),
        getClass().getName() + "$InstrumenterAdvice");
  }

  public static class InstrumenterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.TRUST_BOUNDARY_VIOLATION)
    public static void onEnter(
        @Advice.Argument(0) final String name, @Advice.Argument(1) final Object value) {
      TrustBoundaryViolationModule mod = InstrumentationBridge.TRUST_BOUNDARY_VIOLATION;
      if (mod != null) {
        mod.onSessionValue(name, value);
      }
    }
  }
}
