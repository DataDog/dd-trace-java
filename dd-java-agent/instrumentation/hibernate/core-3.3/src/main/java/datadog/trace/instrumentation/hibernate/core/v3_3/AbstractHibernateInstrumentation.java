package datadog.trace.instrumentation.hibernate.core.v3_3;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.classic.Validatable;
import org.hibernate.transaction.JBossTransactionManagerLookup;

public abstract class AbstractHibernateInstrumentation extends Instrumenter.Default {

  public AbstractHibernateInstrumentation() {
    super("hibernate", "hibernate-core");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.hibernate.Session");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.hibernate.SessionMethodUtils",
      "datadog.trace.instrumentation.hibernate.SessionState",
      "datadog.trace.instrumentation.hibernate.HibernateDecorator",
      packageName + ".AbstractHibernateInstrumentation$V3Advice",
    };
  }

  public abstract static class V3Advice {

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
