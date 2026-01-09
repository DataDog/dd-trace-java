package datadog.trace.instrumentation.resilience4j.timelimiter;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.resilience4j.common.WrapperWithContext;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

public final class TimeLimiterInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String TIME_LIMITER_FQCN = "io.github.resilience4j.timelimiter.TimeLimiter";
  private static final String THIS_CLASS = TimeLimiterInstrumentation.class.getName();

  @Override
  public String instrumentedType() {
    return TIME_LIMITER_FQCN;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("decorateFutureSupplier"))
            .and(takesArgument(0, named(Supplier.class.getName())))
            .and(returns(named(Supplier.class.getName()))),
        THIS_CLASS + "$FutureSupplierAdvice");
  }

  public static class FutureSupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.This TimeLimiter timeLimiter,
        @Advice.Return(readOnly = false) Supplier<Future<?>> result) {
      result = new WrapperWithContext.SupplierOfFutureWithContext<>(
          result, TimeLimiterDecorator.DECORATE, timeLimiter);
    }
  }
}
