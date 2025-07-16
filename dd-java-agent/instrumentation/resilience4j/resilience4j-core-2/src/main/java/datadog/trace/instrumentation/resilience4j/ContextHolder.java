package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public abstract class ContextHolder<T> {

  public static final class CheckedSupplierWithContext<T> extends ContextHolder<T>
      implements CheckedSupplier<Object> {
    private final CheckedSupplier<?> outbound;

    public CheckedSupplierWithContext(
        CheckedSupplier<?> outbound, AbstractResilience4jDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public Object get() throws Throwable {
      try (AgentScope scope = activateScope()) {
        return outbound.get();
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class SupplierWithContext<T> extends ContextHolder<T>
      implements Supplier<Object> {
    private final Supplier<?> outbound;

    public SupplierWithContext(
        Supplier<?> outbound, AbstractResilience4jDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public Object get() {
      try (AgentScope ignore = activateScope()) {
        return outbound.get();
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class SupplierCompletionStageWithContext<T> extends ContextHolder<T>
      implements Supplier<CompletionStage<?>> {
    private final Supplier<CompletionStage<?>> outbound;

    public SupplierCompletionStageWithContext(
        Supplier<CompletionStage<?>> outbound,
        AbstractResilience4jDecorator<T> spanDecorator,
        T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public CompletionStage<?> get() {
      try (AgentScope ignore = activateScope()) {
        return outbound.get();
      } finally {
        finishSpanIfNeeded(); // TODO should postpone this by using wrapAsyncResultOrFinishSpan
      }
    }
  }

  private final AbstractResilience4jDecorator<T> spanDecorator;
  private final T data;
  private AgentSpan span;

  public ContextHolder(AbstractResilience4jDecorator<T> spanDecorator, T data) {
    this.spanDecorator = spanDecorator;
    this.data = data;
  }

  protected AgentScope activateScope() {
    AgentSpan current = ActiveResilience4jSpan.current();
    if (current == null) {
      current = ActiveResilience4jSpan.start();
      this.span = current;
      //      spanDecorator.afterStart(current);
    }
    //    spanDecorator.decorate(span, data);
    return AgentTracer.activateSpan(current);
  }

  protected void finishSpanIfNeeded() {
    if (span != null) {
      ActiveResilience4jSpan.finish(span);
      span = null;
    }
  }
}
