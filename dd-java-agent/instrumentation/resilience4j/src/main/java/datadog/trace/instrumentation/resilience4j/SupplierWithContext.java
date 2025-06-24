package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.function.Supplier;

public class SupplierWithContext extends DecoratorWithContext implements Supplier<Object> {
  private final Supplier<?> outbound;

  public SupplierWithContext(Supplier<?> outbound, Supplier<?> inbound) {
    super(inbound);
    this.outbound = outbound;
  }

  @Override
  public Object get() {
    try (AgentScope ignore = activateDecoratorScope()) {
      return outbound.get();
    }
  }
}
