package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.github.resilience4j.core.functions.CheckedFunction;
import io.github.resilience4j.core.functions.CheckedRunnable;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.github.resilience4j.retry.Retry;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class RetryInstrumentation extends Resilience4jInstrumentation {

  private static final String RETRY_FQCN = "io.github.resilience4j.retry.Retry";
  private static final String THIS_CLASS = RetryInstrumentation.class.getName();

  public RetryInstrumentation() {
    super("resilience4j-retry");
  }

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
            .and(returns(named(SUPPLIER_FQCN))),
        THIS_CLASS + "$CompletionStageAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedSupplier"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(CHECKED_SUPPLIER_FQCN))),
        THIS_CLASS + "$CheckedSupplierAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedRunnable"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(CHECKED_RUNNABLE_FQCN))),
        THIS_CLASS + "$CheckedRunnableAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCallable"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(CALLABLE_FQCN))),
        THIS_CLASS + "$CallableAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateSupplier"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(SUPPLIER_FQCN))),
        THIS_CLASS + "$SupplierAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateFunction"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(FUNCTION_FQCN))),
        THIS_CLASS + "$FunctionAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedFunction"))
            .and(takesArgument(0, named(RETRY_FQCN)))
            .and(returns(named(CHECKED_FUNCTION_FQCN))),
        THIS_CLASS + "$CheckedFunctionAdvice");
  }

  public static class SupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) Supplier<?> outbound) {
      outbound = new ContextHolder.SupplierWithContext<>(outbound, RetryDecorator.DECORATE, retry);
    }
  }

  public static class CallableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) Callable<?> outbound) {
      outbound = new ContextHolder.CallableWithContext<>(outbound, RetryDecorator.DECORATE, retry);
    }
  }

  public static class FunctionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) Function<Object, ?> outbound) {
      outbound = new ContextHolder.FunctionWithContext<>(outbound, RetryDecorator.DECORATE, retry);
    }
  }

  public static class CheckedFunctionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) CheckedFunction<Object, ?> outbound) {
      outbound =
          new ContextHolder.CheckedFunctionWithContext<>(outbound, RetryDecorator.DECORATE, retry);
    }
  }

  public static class CheckedSupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) CheckedSupplier<?> outbound) {
      outbound =
          new ContextHolder.CheckedSupplierWithContext<>(outbound, RetryDecorator.DECORATE, retry);
    }
  }

  public static class CheckedRunnableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) CheckedRunnable outbound) {
      outbound =
          new ContextHolder.CheckedRunnableWithContext<>(outbound, RetryDecorator.DECORATE, retry);
    }
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Retry retry,
        @Advice.Return(readOnly = false) Supplier<CompletionStage<?>> outbound) {
      outbound =
          new ContextHolder.SupplierCompletionStageWithContext<>(
              outbound, RetryDecorator.DECORATE, retry);
    }
  }
}
