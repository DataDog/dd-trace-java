package datadog.trace.instrumentation.hibernate.core.v4_0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.hibernate.SessionMethodUtils;
import datadog.trace.instrumentation.hibernate.SessionState;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.SharedSessionContract;
import org.hibernate.Transaction;

@AutoService(Instrumenter.class)
public class TransactionInstrumentation extends AbstractHibernateInstrumentation {

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.hibernate.Transaction", SESSION_STATE);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.hibernate.engine.transaction.spi.AbstractTransactionImpl",
      "org.hibernate.engine.transaction.internal.jta.CMTTransaction",
      "org.hibernate.engine.transaction.internal.jdbc.JdbcTransaction",
      "org.hibernate.engine.transaction.internal.jta.JtaTransaction"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.hibernate.Transaction";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("commit")).and(takesArguments(0)),
        TransactionInstrumentation.class.getName() + "$TransactionCommitAdvice");
  }

  public static class TransactionCommitAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SessionState startCommit(@Advice.This final Transaction transaction) {

      final ContextStore<Transaction, SessionState> contextStore =
          InstrumentationContext.get(Transaction.class, SessionState.class);

      return SessionMethodUtils.startScopeFrom(
          contextStore, transaction, "hibernate.transaction.commit", null, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endCommit(
        @Advice.This final Transaction transaction,
        @Advice.Enter final SessionState state,
        @Advice.Thrown final Throwable throwable) {

      SessionMethodUtils.closeScope(state, throwable, null, true);
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
