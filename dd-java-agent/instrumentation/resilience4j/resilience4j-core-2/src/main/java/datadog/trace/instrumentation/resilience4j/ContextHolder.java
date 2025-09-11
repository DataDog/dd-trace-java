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

public class ContextHolder<T> {

  public static final class CheckedConsumerWithContext<T> extends ContextHolder<T>
      implements CheckedConsumer<Object> {
    private final CheckedConsumer<Object> outbound;

    public CheckedConsumerWithContext(
        CheckedConsumer<Object> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public void accept(Object arg) throws Throwable {
      try (AgentScope ignore = activateScope()) {
        outbound.accept(arg);
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class ConsumerWithContext<T> extends ContextHolder<T>
      implements Consumer<Object> {
    private final Consumer<Object> outbound;

    public ConsumerWithContext(
        Consumer<Object> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public void accept(Object arg) {
      try (AgentScope ignore = activateScope()) {
        outbound.accept(arg);
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class CheckedFunctionWithContext<T> extends ContextHolder<T>
      implements CheckedFunction<Object, Object> {
    private final CheckedFunction<Object, ?> outbound;

    public CheckedFunctionWithContext(
        CheckedFunction<Object, ?> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public Object apply(Object arg) throws Throwable {
      try (AgentScope ignore = activateScope()) {
        return outbound.apply(arg);
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class SupplierWithContext<T> extends ContextHolder<T>
      implements Supplier<Object> {
    private final Supplier<?> outbound;

    public SupplierWithContext(
        Supplier<?> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
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

  public static final class CallableWithContext<T> extends ContextHolder<T>
      implements Callable<Object> {
    private final Callable<?> outbound;

    public CallableWithContext(
        Callable<?> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public Object call() throws Exception {
      try (AgentScope ignore = activateScope()) {
        return outbound.call();
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class FunctionWithContext<T> extends ContextHolder<T>
      implements Function<Object, Object> {
    private final Function<Object, ?> outbound;

    public FunctionWithContext(
        Function<Object, ?> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
      super(spanDecorator, data);
      this.outbound = outbound;
    }

    @Override
    public Object apply(Object arg) {
      try (AgentScope ignore = activateScope()) {
        return outbound.apply(arg);
      } finally {
        finishSpanIfNeeded();
      }
    }
  }

  public static final class CheckedSupplierWithContext<T> extends ContextHolder<T>
      implements CheckedSupplier<Object> {
    private final CheckedSupplier<?> outbound;

    public CheckedSupplierWithContext(
        CheckedSupplier<?> outbound, Resilience4jSpanDecorator<T> spanDecorator, T data) {
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

  public static final class CheckedRunnableWithContext<T> extends ContextHolder<T>
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

  public static final class SupplierCompletionStageWithContext<T> extends ContextHolder<T>
      implements Supplier<CompletionStage<?>> {
    private final Supplier<CompletionStage<?>> outbound;

    public SupplierCompletionStageWithContext(
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
                  // TODO decorate when error
                  finishSpanIfNeeded();
                });
      }
    }
  }

  private final Resilience4jSpanDecorator<T> spanDecorator;
  private final T data;
  private AgentSpan span;

  public ContextHolder(Resilience4jSpanDecorator<T> spanDecorator, T data) {
    this.spanDecorator = spanDecorator;
    this.data = data;
  }

  public AgentScope activateScope() {
    AgentSpan current = Resilience4jSpan.current();
    AgentSpan owned = current == null ? Resilience4jSpan.start() : null;
    if (owned != null) {
      current = owned;
      spanDecorator.afterStart(owned);
    }
    this.span = owned;
    spanDecorator.decorate(current, data);
    return AgentTracer.activateSpan(current);
  }

  public void finishSpanIfNeeded() {
    if (span != null) {
      spanDecorator.beforeFinish(span);
      Resilience4jSpan.finish(span); // TODO
      span = null;
    }
  }
}
