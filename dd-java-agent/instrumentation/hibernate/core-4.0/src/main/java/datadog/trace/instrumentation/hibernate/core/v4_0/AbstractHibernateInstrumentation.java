package datadog.trace.instrumentation.hibernate.core.v4_0;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.hibernate.CommonMatchers;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.SharedSessionContract;

public abstract class AbstractHibernateInstrumentation extends Instrumenter.Default {

  public AbstractHibernateInstrumentation() {
    super("hibernate", "hibernate-core");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return CommonMatchers.CLASS_LOADER_MATCHER;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.hibernate.SessionMethodUtils",
      "datadog.trace.instrumentation.hibernate.SessionState",
      "datadog.trace.instrumentation.hibernate.HibernateDecorator",
      packageName + ".AbstractHibernateInstrumentation$V4Advice",
    };
  }

  public abstract static class V4Advice {

    /**
     * Some cases of instrumentation will match more broadly than others, so this unused method
     * allows all instrumentation to uniformly match versions of Hibernate starting at 4.0.
     */
    public static void muzzleCheck(final SharedSessionContract contract) {
      contract.createCriteria("");
    }
  }
}
