package datadog.trace.instrumentation.hibernate.core.v4_3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterfaceNamed;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.hibernate.SessionMethodUtils;
import datadog.trace.instrumentation.hibernate.SessionState;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.SharedSessionContract;
import org.hibernate.procedure.ProcedureCall;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@AutoService(Instrumenter.class)
public class SessionInstrumentation extends Instrumenter.Default {

  public SessionInstrumentation() {
    super("hibernate", "hibernate-core");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>();
    map.put("org.hibernate.SharedSessionContract", SessionState.class.getName());
    map.put("org.hibernate.procedure.ProcedureCall", SessionState.class.getName());
    return Collections.unmodifiableMap(map);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.hibernate.SessionMethodUtils",
      "datadog.trace.instrumentation.hibernate.SessionState",
      "datadog.trace.agent.decorator.BaseDecorator",
      "datadog.trace.agent.decorator.ClientDecorator",
      "datadog.trace.agent.decorator.DatabaseClientDecorator",
      "datadog.trace.agent.decorator.OrmClientDecorator",
      "datadog.trace.instrumentation.hibernate.HibernateDecorator",
    };
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return hasInterfaceNamed("org.hibernate.SharedSessionContract");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();

    transformers.put(
        isMethod().and(returns(hasInterfaceNamed("org.hibernate.procedure.ProcedureCall"))),
        SessionInstrumentation.class.getName() + "$GetProcedureCallAdvice");

    return transformers;
  }

  public static class GetProcedureCallAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getProcedureCall(
        @Advice.This final SharedSessionContract session,
        @Advice.Return final ProcedureCall returned) {

      final ContextStore<SharedSessionContract, SessionState> sessionContextStore =
          InstrumentationContext.get(SharedSessionContract.class, SessionState.class);
      final ContextStore<ProcedureCall, SessionState> returnedContextStore =
          InstrumentationContext.get(ProcedureCall.class, SessionState.class);

      SessionMethodUtils.attachSpanFromStore(
          sessionContextStore, session, returnedContextStore, returned);
    }
  }
}
