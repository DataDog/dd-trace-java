package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public abstract class ContextHolder {

  public static final CharSequence SPAN_NAME = UTF8BytesString.create("resilience4j");
  public static final String INSTRUMENTATION_NAME = "resilience4j";

  public static final class CheckedSupplierWithContext<T> extends ContextHolder
      implements CheckedSupplier<Object> {
    private final CheckedSupplier<?> outbound;
    private final AbstractResilience4jDecorator<T> spanDecorator;
    private final T data;

    public CheckedSupplierWithContext(
        CheckedSupplier<?> outbound,
        CheckedSupplier<?> inbound,
        AbstractResilience4jDecorator<T> spanDecorator,
        T data) {
      super(inbound);
      this.outbound = outbound;
      this.spanDecorator = spanDecorator;
      this.data = data;
    }

    @Override
    public Object get() throws Throwable {
      try (AgentScope scope = activateDecoratorScope()) {
        spanDecorator.decorate(scope, data);
        return outbound.get();
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class SupplierWithContext extends ContextHolder implements Supplier<Object> {
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

  public static final class SupplierCompletionStageWithContext extends ContextHolder
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

  protected ContextHolder(Object contextHolder) {
    if (contextHolder instanceof ContextHolder) {
      ContextHolder that = (ContextHolder) contextHolder;
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
      // TODO call decorator
      spanHolder[0] = AgentTracer.startSpan(INSTRUMENTATION_NAME, SPAN_NAME);
    }
    return AgentTracer.activateSpan(spanHolder[0]);
  }

  protected void finishSpanIfNeeded() {
    if (isOwner) {
      // TODO call decorator
      spanHolder[0].finish();
      spanHolder[0] = null;
    }
  }
}
