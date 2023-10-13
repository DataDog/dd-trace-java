package datadog.trace.instrumentation.hibernate.core.v4_0;

import datadog.trace.agent.tooling.Instrumenter;

public abstract class AbstractHibernateInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {

  static final String SESSION_STATE = "datadog.trace.instrumentation.hibernate.SessionState";

  public AbstractHibernateInstrumentation() {
    super("hibernate", "hibernate-core");
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(true);
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
