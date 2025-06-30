package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public abstract class ContextHolder {

  public static final class CheckedSupplierWithContext<T> extends ContextHolder
      implements CheckedSupplier<Object> {
    private final CheckedSupplier<?> outbound;
    private final AbstractResilience4jDecorator<T> spanDecorator;
    private final T data;

    public CheckedSupplierWithContext(
        CheckedSupplier<?> outbound, AbstractResilience4jDecorator<T> spanDecorator, T data) {
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

    public SupplierWithContext(Supplier<?> outbound) {
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

    public SupplierCompletionStageWithContext(Supplier<CompletionStage<?>> outbound) {
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

  private AgentSpan span;

  protected AgentScope activateDecoratorScope() {
    AgentSpan current = ActiveResilience4jSpan.current();
    if (current == null) {
      current = ActiveResilience4jSpan.start();
      this.span = current;
    }
    return AgentTracer.activateSpan(current);
  }

  protected void finishSpanIfNeeded() {
    if (span != null) {
      ActiveResilience4jSpan.finish(span);
      span = null;
    }
  }
}
