package datadog.trace.instrumentation.hibernate.core.v3_3;

import static java.util.Arrays.asList;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;

public abstract class AbstractHibernateInstrumentation
    implements Instrumenter.HasMethodAdvice, Instrumenter.CanShortcutTypeMatching {

  static final String SESSION_STATE = "datadog.trace.instrumentation.hibernate.SessionState";

  @Override
  public final boolean onlyMatchKnownTypes() {
    return InstrumenterConfig.get()
        .isIntegrationShortcutMatchingEnabled(asList("hibernate", "hibernate-core"), true);
  }
}
