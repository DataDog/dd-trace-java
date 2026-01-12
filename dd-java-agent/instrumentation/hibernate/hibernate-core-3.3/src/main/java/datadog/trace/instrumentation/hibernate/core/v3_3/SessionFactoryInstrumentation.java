package datadog.trace.instrumentation.hibernate.core.v3_3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.hibernate.HibernateDecorator.DECORATOR;
import static datadog.trace.instrumentation.hibernate.HibernateDecorator.HIBERNATE_SESSION;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.hibernate.SessionState;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.Session;
import org.hibernate.StatelessSession;
import org.hibernate.classic.Validatable;
import org.hibernate.transaction.JBossTransactionManagerLookup;

public final class SessionFactoryInstrumentation extends AbstractHibernateInstrumentation {
  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"org.hibernate.impl.SessionFactoryImpl"};
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.hibernate.SessionFactory";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("openSession", "openStatelessSession"))
            .and(takesArguments(0))
            .and(
                returns(
                    namedOneOf("org.hibernate.Session", "org.hibernate.StatelessSession")
                        .or(hasInterface(named("org.hibernate.Session"))))),
        SessionFactoryInstrumentation.class.getName() + "$SessionFactoryAdvice");
  }

  public static class SessionFactoryAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void openSession(@Advice.Return final Object session) {

      final AgentSpan span = startSpan(HIBERNATE_SESSION);
      DECORATOR.afterStart(span);
      DECORATOR.onConnection(span, session);

      if (session instanceof Session) {
        final ContextStore<Session, SessionState> contextStore =
            InstrumentationContext.get(Session.class, SessionState.class);
        contextStore.putIfAbsent((Session) session, new SessionState(span));
      } else if (session instanceof StatelessSession) {
        final ContextStore<StatelessSession, SessionState> contextStore =
            InstrumentationContext.get(StatelessSession.class, SessionState.class);
        contextStore.putIfAbsent((StatelessSession) session, new SessionState(span));
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
