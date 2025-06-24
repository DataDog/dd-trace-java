package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public class SupplierCompletionStageWithContext extends DecoratorWithContext
    implements Supplier<CompletionStage<?>> {
  private final Supplier<CompletionStage<?>> outbound;

  public SupplierCompletionStageWithContext(
      Supplier<CompletionStage<?>> outbound, Supplier<?> inbound) {
    super(inbound);

    this.outbound = outbound;
  }

  @Override
  public CompletionStage<?> get() {
    try (AgentScope ignore = activateDecoratorScope()) {
      return outbound.get();
    }
  }
}
