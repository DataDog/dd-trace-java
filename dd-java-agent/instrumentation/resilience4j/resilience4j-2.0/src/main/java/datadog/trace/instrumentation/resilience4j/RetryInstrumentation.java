package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import io.github.resilience4j.core.functions.CheckedFunction;
import io.github.resilience4j.core.functions.CheckedRunnable;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

public final class RetryInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String RETRY_FQCN = "io.github.resilience4j.retry.Retry";
  private static final String THIS_CLASS = RetryInstrumentation.class.getName();

  @Override
  public String instrumentedType() {
    return RETRY_FQCN;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCompletionStage"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(Supplier.class.getName()))),
        THIS_CLASS + "$CompletionStageAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedSupplier"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named("io.github.resilience4j.core.functions.CheckedSupplier"))),
        THIS_CLASS + "$CheckedSupplierAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedRunnable"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named("io.github.resilience4j.core.functions.CheckedRunnable"))),
        THIS_CLASS + "$CheckedRunnableAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCallable"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(Callable.class.getName()))),
        THIS_CLASS + "$CallableAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateSupplier"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(Supplier.class.getName()))),
        THIS_CLASS + "$SupplierAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateFunction"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(Function.class.getName()))),
        THIS_CLASS + "$FunctionAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedFunction"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named("io.github.resilience4j.core.functions.CheckedFunction"))),
        THIS_CLASS + "$CheckedFunctionAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateRunnable"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(Runnable.class.getName()))),
        THIS_CLASS + "$RunnableAdvice");
  }

  public static class SupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) Supplier<?> result) {
      result = new WrapperWithContext.SupplierWithContext<>(result, RetryDecorator.DECORATE, retry);
    }
  }

  public static class CallableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) Callable<?> result) {
      result = new WrapperWithContext.CallableWithContext<>(result, RetryDecorator.DECORATE, retry);
    }
  }

  public static class FunctionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) Function<?, ?> result) {
      result = new WrapperWithContext.FunctionWithContext<>(result, RetryDecorator.DECORATE, retry);
    }
  }

  public static class CheckedFunctionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) CheckedFunction<?, ?> result) {
      result =
          new WrapperWithContext.CheckedFunctionWithContext<>(
              result, RetryDecorator.DECORATE, retry);
    }
  }

  public static class CheckedSupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) CheckedSupplier<?> result) {
      result =
          new WrapperWithContext.CheckedSupplierWithContext<>(
              result, RetryDecorator.DECORATE, retry);
    }
  }

  public static class CheckedRunnableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) CheckedRunnable result) {
      result =
          new WrapperWithContext.CheckedRunnableWithContext<>(
              result, RetryDecorator.DECORATE, retry);
    }
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) Supplier<CompletionStage<?>> result) {
      result =
          new WrapperWithContext.SupplierOfCompletionStageWithContext<>(
              result, RetryDecorator.DECORATE, retry);
    }
  }

  public static class RunnableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry, @Advice.Return(readOnly = false) Runnable result) {
      result = new WrapperWithContext.RunnableWithContext<>(result, RetryDecorator.DECORATE, retry);
    }
  }
}
