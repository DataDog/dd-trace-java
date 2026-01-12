package datadog.trace.instrumentation.hibernate.core.v3_3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.hibernate.HibernateDecorator.DECORATOR;
import static datadog.trace.instrumentation.hibernate.SessionMethodUtils.SCOPE_ONLY_METHODS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.hibernate.SessionMethodUtils;
import datadog.trace.instrumentation.hibernate.SessionState;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.StatelessSession;
import org.hibernate.Transaction;
import org.hibernate.classic.Validatable;
import org.hibernate.transaction.JBossTransactionManagerLookup;

public final class SessionInstrumentation extends AbstractHibernateInstrumentation {
  @Override
  public String[] knownMatchingTypes() {
    return new String[]{
        "org.hibernate.impl.SessionImpl", "org.hibernate.impl.StatelessSessionImpl"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.hibernate.Session";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(
        namedOneOf("org.hibernate.Session", "org.hibernate.StatelessSession"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("close")).and(takesArguments(0)),
        SessionInstrumentation.class.getName() + "$SessionCloseAdvice");

    // Session synchronous methods we want to instrument.
    transformer.applyAdvice(
        isMethod()
            .and(
                namedOneOf(
                    "save",
                    "replicate",
                    "saveOrUpdate",
                    "update",
                    "merge",
                    "persist",
                    "lock",
                    "refresh",
                    "insert",
                    "delete",
                    // Iterator methods.
                    "iterate",
                    // Lazy-load methods.
                    "immediateLoad",
                    "internalLoad")),
        SessionInstrumentation.class.getName() + "$SessionMethodAdvice");

    // Handle the non-generic 'get' separately.
    transformer.applyAdvice(
        isMethod()
            .and(named("get"))
            .and(returns(named("java.lang.Object")))
            .and(takesArgument(0, named("java.lang.String"))),
        SessionInstrumentation.class.getName() + "$SessionMethodAdvice");

    // These methods return some object that we want to instrument, and so the Advice will pin the
    // current Span to the returned object using a ContextStore.
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("beginTransaction", "getTransaction"))
            .and(returns(named("org.hibernate.Transaction"))),
        SessionInstrumentation.class.getName() + "$GetTransactionAdvice");

    transformer.applyAdvice(
        isMethod().and(returns(hasInterface(named("org.hibernate.Query")))),
        SessionInstrumentation.class.getName() + "$GetQueryAdvice");

    transformer.applyAdvice(
        isMethod().and(returns(hasInterface(named("org.hibernate.Criteria")))),
        SessionInstrumentation.class.getName() + "$GetCriteriaAdvice");
  }

  public static class SessionCloseAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeSession(
        @Advice.This final Object session, @Advice.Thrown final Throwable throwable) {

      SessionState state = null;
      if (session instanceof Session) {
        final ContextStore<Session, SessionState> contextStore =
            InstrumentationContext.get(Session.class, SessionState.class);
        state = contextStore.get((Session) session);
      } else if (session instanceof StatelessSession) {
        final ContextStore<StatelessSession, SessionState> contextStore =
            InstrumentationContext.get(StatelessSession.class, SessionState.class);
        state = contextStore.get((StatelessSession) session);
      }

      if (state == null || state.getSessionSpan() == null) {
        return;
      }
      if (state.getMethodScope() != null) {
        state.getMethodScope().close();
      }

      final AgentSpan span = state.getSessionSpan();
      DECORATOR.onError(span, throwable);
      DECORATOR.beforeFinish(span);
      span.finish();
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

  public static class SessionMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SessionState startMethod(
        @Advice.This final Object session,
        @Advice.Origin("hibernate.#m") final String operationName,
        @Advice.Origin("#m") final String methodName,
        @Advice.Argument(0) final Object entity,
        @Advice.Local("startSpan") boolean startSpan) {

      startSpan = !SCOPE_ONLY_METHODS.contains(methodName);
      if (session instanceof Session) {
        final ContextStore<Session, SessionState> contextStore =
            InstrumentationContext.get(Session.class, SessionState.class);
        return SessionMethodUtils.startScopeFrom(
            contextStore, (Session) session, operationName, entity, startSpan);
      } else if (session instanceof StatelessSession) {
        final ContextStore<StatelessSession, SessionState> contextStore =
            InstrumentationContext.get(StatelessSession.class, SessionState.class);
        return SessionMethodUtils.startScopeFrom(
            contextStore, (StatelessSession) session, operationName, entity, startSpan);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.Enter final SessionState sessionState,
        @Advice.Thrown final Throwable throwable,
        @Advice.Local("startSpan") final boolean startSpan,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) final Object returned) {

      SessionMethodUtils.closeScope(sessionState, throwable, returned, startSpan);
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

  public static class GetQueryAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getQuery(
        @Advice.This final Object session, @Advice.Return final Query query) {

      final ContextStore<Query, SessionState> queryContextStore =
          InstrumentationContext.get(Query.class, SessionState.class);
      if (session instanceof Session) {
        final ContextStore<Session, SessionState> sessionContextStore =
            InstrumentationContext.get(Session.class, SessionState.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (Session) session, queryContextStore, query);
      } else if (session instanceof StatelessSession) {
        final ContextStore<StatelessSession, SessionState> sessionContextStore =
            InstrumentationContext.get(StatelessSession.class, SessionState.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (StatelessSession) session, queryContextStore, query);
      }
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

  public static class GetTransactionAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getTransaction(
        @Advice.This final Object session, @Advice.Return final Transaction transaction) {

      final ContextStore<Transaction, SessionState> transactionContextStore =
          InstrumentationContext.get(Transaction.class, SessionState.class);

      if (session instanceof Session) {
        final ContextStore<Session, SessionState> sessionContextStore =
            InstrumentationContext.get(Session.class, SessionState.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (Session) session, transactionContextStore, transaction);
      } else if (session instanceof StatelessSession) {
        final ContextStore<StatelessSession, SessionState> sessionContextStore =
            InstrumentationContext.get(StatelessSession.class, SessionState.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (StatelessSession) session, transactionContextStore, transaction);
      }
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

  public static class GetCriteriaAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void getCriteria(
        @Advice.This final Object session, @Advice.Return final Criteria criteria) {

      final ContextStore<Criteria, SessionState> criteriaContextStore =
          InstrumentationContext.get(Criteria.class, SessionState.class);
      if (session instanceof Session) {
        final ContextStore<Session, SessionState> sessionContextStore =
            InstrumentationContext.get(Session.class, SessionState.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (Session) session, criteriaContextStore, criteria);
      } else if (session instanceof StatelessSession) {
        final ContextStore<StatelessSession, SessionState> sessionContextStore =
            InstrumentationContext.get(StatelessSession.class, SessionState.class);
        SessionMethodUtils.attachSpanFromStore(
            sessionContextStore, (StatelessSession) session, criteriaContextStore, criteria);
      }
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
