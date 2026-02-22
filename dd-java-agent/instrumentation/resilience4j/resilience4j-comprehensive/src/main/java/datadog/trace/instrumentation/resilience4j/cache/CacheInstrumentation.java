package datadog.trace.instrumentation.resilience4j.cache;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.resilience4j.common.WrapperWithContext;
import io.github.resilience4j.cache.Cache;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

public final class CacheInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String CACHE_FQCN = "io.github.resilience4j.cache.Cache";
  private static final String THIS_CLASS = CacheInstrumentation.class.getName();

  @Override
  public String instrumentedType() {
    return CACHE_FQCN;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateSupplier"))
            .and(takesArgument(0, named(CACHE_FQCN)))
            .and(returns(named(Supplier.class.getName()))),
        THIS_CLASS + "$SupplierAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCallable"))
            .and(takesArgument(0, named(CACHE_FQCN)))
            .and(returns(named(Callable.class.getName()))),
        THIS_CLASS + "$CallableAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedSupplier"))
            .and(takesArgument(0, named(CACHE_FQCN)))
            .and(returns(named("io.github.resilience4j.core.functions.CheckedSupplier"))),
        THIS_CLASS + "$CheckedSupplierAdvice");
  }

  public static class SupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Cache<?> cache,
        @Advice.Return(readOnly = false) Supplier<?> result) {
      result = new WrapperWithContext.SupplierWithContext<>(
          result, CacheDecorator.DECORATE, cache);
    }
  }

  public static class CallableAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Cache<?> cache,
        @Advice.Return(readOnly = false) Callable<?> result) {
      result = new WrapperWithContext.CallableWithContext<>(
          result, CacheDecorator.DECORATE, cache);
    }
  }

  public static class CheckedSupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) Cache<?> cache,
        @Advice.Return(readOnly = false) CheckedSupplier<?> result) {
      result = new WrapperWithContext.CheckedSupplierWithContext<>(
          result, CacheDecorator.DECORATE, cache);
    }
  }
}
