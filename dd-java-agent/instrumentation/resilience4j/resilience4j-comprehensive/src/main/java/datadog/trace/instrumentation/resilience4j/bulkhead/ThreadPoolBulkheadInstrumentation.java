package datadog.trace.instrumentation.resilience4j.bulkhead;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.resilience4j.common.WrapperWithContext;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

public final class ThreadPoolBulkheadInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String THREAD_POOL_BULKHEAD_FQCN =
      "io.github.resilience4j.bulkhead.ThreadPoolBulkhead";
  private static final String THIS_CLASS = ThreadPoolBulkheadInstrumentation.class.getName();

  @Override
  public String instrumentedType() {
    return THREAD_POOL_BULKHEAD_FQCN;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(named("decorateSupplier")))
            .and(named("decorateCallable"))
            .and(takesArgument(0, named(Callable.class.getName())))
            .and(returns(named(Callable.class.getName()))),
        THIS_CLASS + "$CallableAdvice");
  }

  public static class CallableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.This ThreadPoolBulkhead bulkhead,
        @Advice.Return(readOnly = false) Callable<?> result) {
      result = new WrapperWithContext.CallableWithContext<>(
          result, ThreadPoolBulkheadDecorator.DECORATE, bulkhead);
    }
  }
}
