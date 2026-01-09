package datadog.trace.instrumentation.resilience4j.bulkhead;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.resilience4j.common.WrapperWithContext;
import io.github.resilience4j.bulkhead.Bulkhead;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

public final class BulkheadInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String BULKHEAD_FQCN = "io.github.resilience4j.bulkhead.Bulkhead";
  private static final String THIS_CLASS = BulkheadInstrumentation.class.getName();

  @Override
  public String instrumentedType() {
    return BULKHEAD_FQCN;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateSupplier"))
            .and(takesArgument(0, named(BULKHEAD_FQCN)))
            .and(returns(named(Supplier.class.getName()))),
        THIS_CLASS + "$SupplierAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCallable"))
            .and(takesArgument(0, named(BULKHEAD_FQCN)))
            .and(returns(named(Callable.class.getName()))),
        THIS_CLASS + "$CallableAdvice");
  }

  public static class SupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Bulkhead bulkhead,
        @Advice.Return(readOnly = false) Supplier<?> result) {
      result = new WrapperWithContext.SupplierWithContext<>(
          result, BulkheadDecorator.DECORATE, bulkhead);
    }
  }

  public static class CallableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Bulkhead bulkhead,
        @Advice.Return(readOnly = false) Callable<?> result) {
      result = new WrapperWithContext.CallableWithContext<>(
          result, BulkheadDecorator.DECORATE, bulkhead);
    }
  }
}
