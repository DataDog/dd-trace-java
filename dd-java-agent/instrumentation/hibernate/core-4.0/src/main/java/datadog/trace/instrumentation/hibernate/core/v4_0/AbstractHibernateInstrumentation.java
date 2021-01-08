package datadog.trace.instrumentation.hibernate.core.v4_0;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.hibernate.HibernateMatchers;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class AbstractHibernateInstrumentation extends Instrumenter.Tracing {

  public AbstractHibernateInstrumentation() {
    super("hibernate", "hibernate-core");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return HibernateMatchers.CLASS_LOADER_MATCHER;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.hibernate.SessionMethodUtils",
      "datadog.trace.instrumentation.hibernate.SessionState",
      "datadog.trace.instrumentation.hibernate.HibernateDecorator",
    };
  }
}
