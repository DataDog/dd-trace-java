package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

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
  private boolean isOwner;

  protected AgentScope activateDecoratorScope() {
    AgentSpan activeSpan = activeSpan();
    if (activeSpan != null && SPAN_NAME.equals(activeSpan.getOperationName())) {
      // a Resilience4j span is already active
      this.span = activeSpan;
      isOwner = false;
    } else {
      // TODO call decorator
      this.span = AgentTracer.startSpan(INSTRUMENTATION_NAME, SPAN_NAME);
      isOwner = true;
    }
    return AgentTracer.activateSpan(this.span);
  }

  protected void finishSpanIfNeeded() {
    if (isOwner) {
      // TODO call decorator
      span.finish();
    }
  }
}
