package datadog.trace.instrumentation.hibernate.core.v3_3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.instrumentation.hibernate.HibernateDecorator.DECORATOR;
import static datadog.trace.instrumentation.hibernate.SessionMethodUtils.SCOPE_ONLY_METHODS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.hibernate.SessionMethodUtils;
import datadog.trace.instrumentation.hibernate.SessionState;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.StatelessSession;
import org.hibernate.Transaction;

@AutoService(Instrumenter.class)
public class SessionInstrumentation extends AbstractHibernateInstrumentation {

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> map = new HashMap<>();
    map.put("org.hibernate.Session", SessionState.class.getName());
    map.put("org.hibernate.StatelessSession", SessionState.class.getName());
    map.put("org.hibernate.Query", SessionState.class.getName());
    map.put("org.hibernate.Transaction", SessionState.class.getName());
    map.put("org.hibernate.Criteria", SessionState.class.getName());
    return Collections.unmodifiableMap(map);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(
        named("org.hibernate.Session").or(named("org.hibernate.StatelessSession")));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod().and(named("close")).and(takesArguments(0)),
        SessionInstrumentation.class.getName() + "$SessionCloseAdvice");

    // Session synchronous methods we want to instrument.
    transformers.put(
        isMethod()
            .and(
                named("save")
                    .or(named("replicate"))
                    .or(named("saveOrUpdate"))
                    .or(named("update"))
                    .or(named("merge"))
                    .or(named("persist"))
                    .or(named("lock"))
                    .or(named("refresh"))
                    .or(named("insert"))
                    .or(named("delete"))
                    // Iterator methods.
                    .or(named("iterate"))
                    // Lazy-load methods.
                    .or(named("immediateLoad"))
                    .or(named("internalLoad"))),
        SessionInstrumentation.class.getName() + "$SessionMethodAdvice");

    // Handle the non-generic 'get' separately.
    transformers.put(
        isMethod()
            .and(named("get"))
            .and(returns(named("java.lang.Object")))
            .and(takesArgument(0, named("java.lang.String"))),
        SessionInstrumentation.class.getName() + "$SessionMethodAdvice");

    // These methods return some object that we want to instrument, and so the Advice will pin the
    // current Span to the returned object using a ContextStore.
    transformers.put(
        isMethod()
            .and(named("beginTransaction").or(named("getTransaction")))
            .and(returns(named("org.hibernate.Transaction"))),
        SessionInstrumentation.class.getName() + "$GetTransactionAdvice");

    transformers.put(
        isMethod().and(returns(hasInterface(named("org.hibernate.Query")))),
        SessionInstrumentation.class.getName() + "$GetQueryAdvice");

    transformers.put(
        isMethod().and(returns(hasInterface(named("org.hibernate.Criteria")))),
        SessionInstrumentation.class.getName() + "$GetCriteriaAdvice");

    return transformers;
  }

  public static class SessionCloseAdvice extends V3Advice {

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
  }

  public static class SessionMethodAdvice extends V3Advice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SessionState startMethod(
        @Advice.This final Object session,
        @Advice.Origin("#m") final String name,
        @Advice.Argument(0) final Object entity) {

      final boolean startSpan = !SCOPE_ONLY_METHODS.contains(name);
      if (session instanceof Session) {
        final ContextStore<Session, SessionState> contextStore =
            InstrumentationContext.get(Session.class, SessionState.class);
        return SessionMethodUtils.startScopeFrom(
            contextStore, (Session) session, "hibernate." + name, entity, startSpan);
      } else if (session instanceof StatelessSession) {
        final ContextStore<StatelessSession, SessionState> contextStore =
            InstrumentationContext.get(StatelessSession.class, SessionState.class);
        return SessionMethodUtils.startScopeFrom(
            contextStore, (StatelessSession) session, "hibernate." + name, entity, startSpan);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.Enter final SessionState sessionState,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) final Object returned) {

      SessionMethodUtils.closeScope(sessionState, throwable, returned);
    }
  }

  public static class GetQueryAdvice extends V3Advice {

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
  }

  public static class GetTransactionAdvice extends V3Advice {

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
  }

  public static class GetCriteriaAdvice extends V3Advice {

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
  }
}
