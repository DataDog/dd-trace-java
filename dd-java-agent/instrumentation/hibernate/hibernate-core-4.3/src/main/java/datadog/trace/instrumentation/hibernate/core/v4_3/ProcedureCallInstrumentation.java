package datadog.trace.instrumentation.hibernate.core.v4_3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.hibernate.core.v4_3.SessionInstrumentation.SESSION_STATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.hibernate.SessionMethodUtils;
import datadog.trace.instrumentation.hibernate.SessionState;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.procedure.ProcedureCall;

@AutoService(InstrumenterModule.class)
public class ProcedureCallInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public ProcedureCallInstrumentation() {
    super("hibernate", "hibernate-core");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.hibernate.procedure.ProcedureCall", SESSION_STATE);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.hibernate.SessionMethodUtils",
      "datadog.trace.instrumentation.hibernate.SessionState",
      "datadog.trace.instrumentation.hibernate.HibernateDecorator",
    };
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(true);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"org.hibernate.procedure.internal.ProcedureCallImpl"};
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.hibernate.procedure.ProcedureCall";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getOutputs")),
        ProcedureCallInstrumentation.class.getName() + "$ProcedureCallMethodAdvice");
  }

  public static class ProcedureCallMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SessionState startMethod(
        @Advice.This final ProcedureCall call,
        @Advice.Origin("hibernate.procedure.#m") final String operationName) {

      final ContextStore<ProcedureCall, SessionState> contextStore =
          InstrumentationContext.get(ProcedureCall.class, SessionState.class);

      final SessionState state =
          SessionMethodUtils.startScopeFrom(
              contextStore, call, operationName, call.getProcedureName(), true);
      return state;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.Enter final SessionState state, @Advice.Thrown final Throwable throwable) {
      SessionMethodUtils.closeScope(state, throwable, null, true);
    }
  }
}
