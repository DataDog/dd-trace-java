package datadog.trace.instrumentation.hibernate.core.v3_3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.hibernate.SessionMethodUtils;
import datadog.trace.instrumentation.hibernate.SessionState;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.Criteria;
import org.hibernate.classic.Validatable;
import org.hibernate.transaction.JBossTransactionManagerLookup;

public final class CriteriaInstrumentation extends AbstractHibernateInstrumentation {
  @Override
  public String[] knownMatchingTypes() {
    return new String[]{
        "org.hibernate.impl.CriteriaImpl", "org.hibernate.impl.CriteriaImpl$Subcriteria"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.hibernate.Criteria";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("list", "uniqueResult", "scroll")),
        CriteriaInstrumentation.class.getName() + "$CriteriaMethodAdvice");
  }

  public static class CriteriaMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SessionState startMethod(
        @Advice.This final Criteria criteria,
        @Advice.Origin("hibernate.criteria.#m") final String operationName) {

      final ContextStore<Criteria, SessionState> contextStore =
          InstrumentationContext.get(Criteria.class, SessionState.class);

      return SessionMethodUtils.startScopeFrom(contextStore, criteria, operationName, null, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.Enter final SessionState state,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) final Object entity) {

      SessionMethodUtils.closeScope(state, throwable, entity, true);
    }

    /**
     * Some cases of instrumentation will match more broadly than others, so this unused method
     * allows all instrumentation to uniformly match versions of Hibernate between 3.3 and 4.
     */
    public static void muzzleCheck(
        // Not in 4.0
        final Validatable validatable,
        // Not before 3.3.0.GA
        final JBossTransactionManagerLookup lookup) {
      validatable.validate();
      lookup.getUserTransactionName();
    }
  }
}
