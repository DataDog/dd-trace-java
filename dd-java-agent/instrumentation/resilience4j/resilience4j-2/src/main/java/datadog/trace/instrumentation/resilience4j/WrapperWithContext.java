package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.github.resilience4j.core.functions.CheckedConsumer;
import io.github.resilience4j.core.functions.CheckedFunction;
import io.github.resilience4j.core.functions.CheckedRunnable;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class WrapperWithContext<T> {

  public static final class CheckedConsumerWithContext<T, I> extends WrapperWithContext<T>
      implements CheckedConsumer<I> {
    private final CheckedConsumer<I> outbound;

    public CheckedConsumerWithContext(
        CheckedConsumer<I> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public void accept(I arg) throws Throwable {
      try (AgentScope ignore = activateScope()) {
        outbound.accept(arg);
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class ConsumerWithContext<T, I> extends WrapperWithContext<T>
      implements Consumer<I> {
    private final Consumer<I> outbound;

    public ConsumerWithContext(
        Consumer<I> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public void accept(I arg) {
      try (AgentScope ignore = activateScope()) {
        outbound.accept(arg);
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class CheckedFunctionWithContext<T, I, O> extends WrapperWithContext<T>
      implements CheckedFunction<I, O> {
    private final CheckedFunction<I, O> outbound;

    public CheckedFunctionWithContext(
        CheckedFunction<I, O> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public O apply(I arg) throws Throwable {
      try (AgentScope ignore = activateScope()) {
        return outbound.apply(arg);
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class SupplierWithContext<T, O> extends WrapperWithContext<T>
      implements Supplier<O> {
    private final Supplier<O> outbound;

    public SupplierWithContext(
        Supplier<O> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public O get() {
      try (AgentScope ignore = activateScope()) {
        return outbound.get();
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class CallableWithContext<T, O> extends WrapperWithContext<T>
      implements Callable<O> {
    private final Callable<O> outbound;

    public CallableWithContext(
        Callable<O> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public O call() throws Exception {
      try (AgentScope ignore = activateScope()) {
        return outbound.call();
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class FunctionWithContext<T, I, O> extends WrapperWithContext<T>
      implements Function<I, O> {
    private final Function<I, O> outbound;

    public FunctionWithContext(
        Function<I, O> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public O apply(I arg) {
      try (AgentScope ignore = activateScope()) {
        return outbound.apply(arg);
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class CheckedSupplierWithContext<T, O> extends WrapperWithContext<T>
      implements CheckedSupplier<O> {
    private final CheckedSupplier<O> outbound;

    public CheckedSupplierWithContext(
        CheckedSupplier<O> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public O get() throws Throwable {
      try (AgentScope scope = activateScope()) {
        return outbound.get();
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class CheckedRunnableWithContext<T> extends WrapperWithContext<T>
      implements CheckedRunnable {
    private final CheckedRunnable outbound;

    public CheckedRunnableWithContext(
        CheckedRunnable outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public void run() throws Throwable {
      try (AgentScope scope = activateScope()) {
        outbound.run();
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class RunnableWithContext<T> extends WrapperWithContext<T>
      implements Runnable {
    private final Runnable outbound;

    public RunnableWithContext(
        Runnable outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public void run() {
      try (AgentScope scope = activateScope()) {
        outbound.run();
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class SupplierOfCompletionStageWithContext<T> extends WrapperWithContext<T>
      implements Supplier<CompletionStage<?>> {
    private final Supplier<CompletionStage<?>> outbound;

    public SupplierOfCompletionStageWithContext(
        Supplier<CompletionStage<?>> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public CompletionStage<?> get() {
      try (AgentScope ignore = activateScope()) {
        return outbound
            .get()
            .whenComplete(
                (v, e) -> {
                  finishSpanIfNeeded();
                });
      }
    }
  }

  private final Resilience4jSpanDecorator<T> spanDecorator;
  private final T data;
  private AgentSpan span;

  protected WrapperWithContext(Resilience4jSpanDecorator<T> spanDecorator, T data) {
    this.spanDecorator = spanDecorator;
    this.data = data;
  }

  public AgentScope activateScope() {
    AgentSpan current = Resilience4jSpan.current();
    AgentSpan owned = current == null ? Resilience4jSpan.start() : null;
    if (owned != null) {
      current = owned;
      spanDecorator.afterStart(owned);
      this.span = owned;
    }
    spanDecorator.decorate(current, data);
    return AgentTracer.activateSpan(current);
  }

  public void finishSpanIfNeeded() {
    if (span != null) {
      spanDecorator.beforeFinish(span);
      Resilience4jSpan.finish(span);
      span = null;
    }
  }
}
