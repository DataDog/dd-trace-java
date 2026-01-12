package datadog.trace.instrumentation.hibernate.core.v4_3;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Instrumentation module for Hibernate Core 4.3 support.
 *
 * <p>This module coordinates all Hibernate 4.3 tracing instrumentations and provides shared
 * configuration.
 */
@AutoService(InstrumenterModule.class)
public final class HibernateModule extends InstrumenterModule.Tracing {

  static final String SESSION_STATE = "datadog.trace.instrumentation.hibernate.SessionState";

  public HibernateModule() {
    super("hibernate", "hibernate-core");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> stores = new HashMap<>();
    stores.put("org.hibernate.SharedSessionContract", SESSION_STATE);
    stores.put("org.hibernate.procedure.ProcedureCall", SESSION_STATE);
    return Collections.unmodifiableMap(stores);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.hibernate.SessionMethodUtils",
      "datadog.trace.instrumentation.hibernate.SessionState",
      "datadog.trace.instrumentation.hibernate.HibernateDecorator",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new SessionInstrumentation(),
        new ProcedureCallInstrumentation());
  }
}
