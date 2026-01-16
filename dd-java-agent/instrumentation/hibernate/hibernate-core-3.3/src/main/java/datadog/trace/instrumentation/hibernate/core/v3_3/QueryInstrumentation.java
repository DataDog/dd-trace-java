package datadog.trace.instrumentation.hibernate.core.v3_3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.hibernate.HibernateDecorator.DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.hibernate.SessionMethodUtils;
import datadog.trace.instrumentation.hibernate.SessionState;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.Query;
import org.hibernate.SQLQuery;
import org.hibernate.classic.Validatable;
import org.hibernate.transaction.JBossTransactionManagerLookup;

public final class QueryInstrumentation extends AbstractHibernateInstrumentation {
  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.hibernate.impl.AbstractQueryImpl",
      "org.hibernate.impl.CollectionFilterImpl",
      "org.hibernate.impl.QueryImpl",
      "org.hibernate.impl.SQLQueryImpl"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.hibernate.Query";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("list", "executeUpdate", "uniqueResult", "scroll")),
        QueryInstrumentation.class.getName() + "$QueryMethodAdvice");
  }

  public static class QueryMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SessionState startMethod(
        @Advice.This final Query query,
        @Advice.Origin("hibernate.query.#m") final String operationName) {

      final ContextStore<Query, SessionState> contextStore =
          InstrumentationContext.get(Query.class, SessionState.class);

      // Note: We don't know what the entity is until the method is returning.
      final SessionState state =
          SessionMethodUtils.startScopeFrom(contextStore, query, operationName, null, true);
      if (state != null) {
        DECORATOR.onStatement(state.getMethodScope().span(), query.getQueryString());
      }
      return state;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.This final Query query,
        @Advice.Enter final SessionState state,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) final Object returned) {

      Object entity = returned;
      if (returned == null || query instanceof SQLQuery) {
        // Not a method that returns results, or the query returns a table rather than an ORM
        // object.
        entity = query.getQueryString();
      }

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
