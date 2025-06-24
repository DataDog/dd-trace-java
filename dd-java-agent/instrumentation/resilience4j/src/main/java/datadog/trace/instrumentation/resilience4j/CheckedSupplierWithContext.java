package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.github.resilience4j.core.functions.CheckedSupplier;

public class CheckedSupplierWithContext extends DecoratorWithContext
    implements CheckedSupplier<Object> {
  private final CheckedSupplier<?> outbound;

  public CheckedSupplierWithContext(CheckedSupplier<?> outbound, CheckedSupplier<?> inbound) {
    super(inbound);
    this.outbound = outbound;
  }

  @Override
  public Object get() throws Throwable {
    try (AgentScope ignore = activateDecoratorScope()) {
      return outbound.get();
    }
  }
}
