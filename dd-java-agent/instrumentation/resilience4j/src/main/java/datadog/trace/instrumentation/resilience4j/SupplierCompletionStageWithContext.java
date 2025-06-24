package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public class SupplierCompletionStageWithContext implements Supplier<CompletionStage<?>> {
  private final Supplier<CompletionStage<?>> outbound;
  private boolean spanOwner = true;

  // use an array as a span holder, so it can be created in the innermost but initialized and
  // finished in the outermost decorator
  private AgentSpan[] spanHolder = {null};

  public SupplierCompletionStageWithContext(
      Supplier<CompletionStage<?>> outbound, Supplier<?> inbound) {
    if (inbound instanceof SupplierCompletionStageWithContext) {
      SupplierCompletionStageWithContext inboundWithContext =
          (SupplierCompletionStageWithContext) inbound;
      inboundWithContext.spanOwner = false;
      this.spanHolder = inboundWithContext.spanHolder;
    }
    this.outbound = outbound;
  }

  @Override
  public CompletionStage<?> get() {
    // TODO move this to the decorator
    if (spanHolder[0] == null) {
      spanHolder[0] = AgentTracer.startSpan(DDContext.INSTRUMENTATION_NAME, DDContext.SPAN_NAME);
    }
    try (AgentScope ignore = AgentTracer.activateSpan(spanHolder[0])) {
      return outbound.get();
    } finally {
      if (spanOwner) {
        // TODO move this to the decorator
        spanHolder[0].finish();
        spanHolder[0] = null;
      }
    }
  }
}
