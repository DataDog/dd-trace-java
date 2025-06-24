package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public abstract class DecoratorWithContext {

  public static class CheckedSupplierWithContext extends DecoratorWithContext
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
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static class SupplierWithContext extends DecoratorWithContext implements Supplier<Object> {
    private final Supplier<?> outbound;

    public SupplierWithContext(Supplier<?> outbound, Supplier<?> inbound) {
      super(inbound);
      this.outbound = outbound;
    }

    @Override
    public Object get() {
      try (AgentScope ignore = activateDecoratorScope()) {
        return outbound.get();
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static class SupplierCompletionStageWithContext extends DecoratorWithContext
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
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  // Use an array so that it can be created and referenced before the span. This is necessary
  // because the holder is created when the innermost decorator is initialized, and then it is
  // referenced in the outer decorators until the outermost one, where the span is created and
  // finished.
  private final AgentSpan[] spanHolder;
  private boolean isOwner = true;

  protected DecoratorWithContext(Object contextHolder) {
    if (contextHolder instanceof DecoratorWithContext) {
      DecoratorWithContext that = (DecoratorWithContext) contextHolder;
      this.spanHolder = that.takeOwnership();
    } else {
      this.spanHolder = new AgentSpan[] {null};
    }
  }

  private AgentSpan[] takeOwnership() {
    isOwner = false;
    return spanHolder;
  }

  protected AgentScope activateDecoratorScope() {
    if (spanHolder[0] == null) {
      // TODO move this to the decorator
      spanHolder[0] = AgentTracer.startSpan(DDContext.INSTRUMENTATION_NAME, DDContext.SPAN_NAME);
    }
    return AgentTracer.activateSpan(spanHolder[0]);
  }

  protected void finishSpanIfNeeded() {
    if (isOwner) {
      // TODO move this to the decorator
      spanHolder[0].finish();
      spanHolder[0] = null;
    }
  }
}
