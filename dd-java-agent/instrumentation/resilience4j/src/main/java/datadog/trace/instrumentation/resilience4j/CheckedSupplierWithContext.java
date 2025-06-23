package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.github.resilience4j.core.functions.CheckedSupplier;

// TODO rename to ContextHolder and implement all the interfaces to minimize number of classes
// needed?
public class CheckedSupplierWithContext implements CheckedSupplier<Object> {
  private final CheckedSupplier<?> outbound;
  private boolean spanOwner = true;
  private AgentSpan[] spanHolder = {
    null
  }; // use an array as a span holder, so it can be created in the innermost but initialized and
  // finished in the outermost decorator

  // TODO pass decorator (and an object like circuit-breaker or retry) to the constructor
  public CheckedSupplierWithContext(CheckedSupplier<?> outbound, CheckedSupplier<?> inbound) {
    if (inbound instanceof CheckedSupplierWithContext) {
      CheckedSupplierWithContext inboundWithContext = (CheckedSupplierWithContext) inbound;
      inboundWithContext.spanOwner = false;
      this.spanHolder = inboundWithContext.spanHolder;
    }
    this.outbound = outbound;
  }

  @Override
  public Object get() throws Throwable {
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
