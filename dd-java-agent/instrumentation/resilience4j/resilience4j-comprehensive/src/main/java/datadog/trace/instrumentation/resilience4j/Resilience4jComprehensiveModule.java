package datadog.trace.instrumentation.resilience4j;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class Resilience4jComprehensiveModule extends InstrumenterModule.Tracing {

  public Resilience4jComprehensiveModule() {
    super("resilience4j", "resilience4j-comprehensive");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      // Common infrastructure
      packageName + ".common.WrapperWithContext",
      packageName + ".common.WrapperWithContext$CallableWithContext",
      packageName + ".common.WrapperWithContext$CheckedRunnableWithContext",
      packageName + ".common.WrapperWithContext$RunnableWithContext",
      packageName + ".common.WrapperWithContext$CheckedFunctionWithContext",
      packageName + ".common.WrapperWithContext$ConsumerWithContext",
      packageName + ".common.WrapperWithContext$CheckedSupplierWithContext",
      packageName + ".common.WrapperWithContext$CheckedConsumerWithContext",
      packageName + ".common.WrapperWithContext$FunctionWithContext",
      packageName + ".common.WrapperWithContext$SupplierOfCompletionStageWithContext",
      packageName + ".common.WrapperWithContext$SupplierWithContext",
      packageName + ".common.WrapperWithContext$SupplierOfFutureWithContext",
      packageName + ".common.WrapperWithContext$FinishOnGetFuture",
      packageName + ".common.Resilience4jSpanDecorator",
      packageName + ".common.Resilience4jSpan",

      // Component decorators
      packageName + ".circuitbreaker.CircuitBreakerDecorator",
      packageName + ".retry.RetryDecorator",
      packageName + ".ratelimiter.RateLimiterDecorator",
      packageName + ".bulkhead.BulkheadDecorator",
      packageName + ".bulkhead.ThreadPoolBulkheadDecorator",
      packageName + ".timelimiter.TimeLimiterDecorator",
      packageName + ".cache.CacheDecorator",
      packageName + ".hedge.HedgeDecorator",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new datadog.trace.instrumentation.resilience4j.circuitbreaker.CircuitBreakerInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.retry.RetryInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.ratelimiter.RateLimiterInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.bulkhead.BulkheadInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.bulkhead.ThreadPoolBulkheadInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.timelimiter.TimeLimiterInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.cache.CacheInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.hedge.HedgeInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.fallback.FallbackCallableInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.fallback.FallbackSupplierInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.fallback.FallbackCheckedSupplierInstrumentation(),
        new datadog.trace.instrumentation.resilience4j.fallback.FallbackCompletionStageInstrumentation());
  }
}
