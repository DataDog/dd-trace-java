package datadog.trace.instrumentation.resilience4j.circuitbreaker;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.resilience4j.common.WrapperWithContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.functions.CheckedConsumer;
import io.github.resilience4j.core.functions.CheckedFunction;
import io.github.resilience4j.core.functions.CheckedRunnable;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

public final class CircuitBreakerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String CIRCUIT_BREAKER_FQCN =
      "io.github.resilience4j.circuitbreaker.CircuitBreaker";
  private static final String THIS_CLASS = CircuitBreakerInstrumentation.class.getName();

  @Override
  public String instrumentedType() {
    return CIRCUIT_BREAKER_FQCN;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateSupplier"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named(Supplier.class.getName()))),
        THIS_CLASS + "$SupplierAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCallable"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named(Callable.class.getName()))),
        THIS_CLASS + "$CallableAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateRunnable"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named(Runnable.class.getName()))),
        THIS_CLASS + "$RunnableAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateFunction"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named(Function.class.getName()))),
        THIS_CLASS + "$FunctionAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateConsumer"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named(Consumer.class.getName()))),
        THIS_CLASS + "$ConsumerAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedSupplier"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named("io.github.resilience4j.core.functions.CheckedSupplier"))),
        THIS_CLASS + "$CheckedSupplierAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedRunnable"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named("io.github.resilience4j.core.functions.CheckedRunnable"))),
        THIS_CLASS + "$CheckedRunnableAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedFunction"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named("io.github.resilience4j.core.functions.CheckedFunction"))),
        THIS_CLASS + "$CheckedFunctionAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedConsumer"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named("io.github.resilience4j.core.functions.CheckedConsumer"))),
        THIS_CLASS + "$CheckedConsumerAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCompletionStage"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named(Supplier.class.getName()))),
        THIS_CLASS + "$CompletionStageAdvice");
  }

  public static class SupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) Supplier<?> result) {
      result = new WrapperWithContext.SupplierWithContext<>(
          result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class CallableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) Callable<?> result) {
      result = new WrapperWithContext.CallableWithContext<>(
          result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class RunnableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) Runnable result) {
      result = new WrapperWithContext.RunnableWithContext<>(
          result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class FunctionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) Function<?, ?> result) {
      result = new WrapperWithContext.FunctionWithContext<>(
          result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class ConsumerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) Consumer<?> result) {
      result = new WrapperWithContext.ConsumerWithContext<>(
          result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class CheckedSupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) CheckedSupplier<?> result) {
      result = new WrapperWithContext.CheckedSupplierWithContext<>(
          result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class CheckedRunnableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) CheckedRunnable result) {
      result = new WrapperWithContext.CheckedRunnableWithContext<>(
          result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class CheckedFunctionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) CheckedFunction<?, ?> result) {
      result = new WrapperWithContext.CheckedFunctionWithContext<>(
          result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class CheckedConsumerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) CheckedConsumer<?> result) {
      result = new WrapperWithContext.CheckedConsumerWithContext<>(
          result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) Supplier<CompletionStage<?>> result) {
      result = new WrapperWithContext.SupplierOfCompletionStageWithContext<>(
          result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }
}
