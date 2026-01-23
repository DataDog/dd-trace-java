package datadog.trace.instrumentation.hibernate.core.v4_0;

import static java.util.Arrays.asList;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;

public abstract class AbstractHibernateInstrumentation
    implements Instrumenter.HasMethodAdvice, Instrumenter.CanShortcutTypeMatching {
  @Override
  public final boolean onlyMatchKnownTypes() {
    return InstrumenterConfig.get()
        .isIntegrationShortcutMatchingEnabled(asList("hibernate", "hibernate-core"), true);
  }
}
