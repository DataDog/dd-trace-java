package datadog.trace.instrumentation.hibernate.core.v4_0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.SqlInjectionModule;
import net.bytebuddy.asm.Advice;
import org.hibernate.Query;
import org.hibernate.SharedSessionContract;

@AutoService(InstrumenterModule.class)
public class IastQueryInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public IastQueryInstrumentation() {
    super("hibernate", "hibernate-core");
  }

  @Override
  public String instrumentedType() {
    return "org.hibernate.internal.AbstractQueryImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("before").and(takesArguments(0))),
        IastQueryInstrumentation.class.getName() + "$QueryMethodAdvice");
  }

  public static class QueryMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.SQL_INJECTION)
    public static void beforeMethod(@Advice.This final Query query) {
      final SqlInjectionModule module = InstrumentationBridge.SQL_INJECTION;
      if (module != null) {
        module.onJdbcQuery(query.getQueryString());
      }
    }

    /**
     * Some cases of instrumentation will match more broadly than others, so this unused method
     * allows all instrumentation to uniformly match versions of Hibernate starting at 4.0.
     */
    public static void muzzleCheck(final SharedSessionContract contract) {
      contract.createCriteria("");
    }
  }
}
