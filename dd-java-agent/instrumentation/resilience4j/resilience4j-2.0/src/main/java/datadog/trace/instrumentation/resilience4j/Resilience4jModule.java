package datadog.trace.instrumentation.resilience4j;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class Resilience4jModule extends InstrumenterModule.Tracing {

  public Resilience4jModule() {
    super("resilience4j");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".WrapperWithContext",
      packageName + ".WrapperWithContext$CallableWithContext",
      packageName + ".WrapperWithContext$CheckedRunnableWithContext",
      packageName + ".WrapperWithContext$RunnableWithContext",
      packageName + ".WrapperWithContext$CheckedFunctionWithContext",
      packageName + ".WrapperWithContext$ConsumerWithContext",
      packageName + ".WrapperWithContext$CheckedSupplierWithContext",
      packageName + ".WrapperWithContext$CheckedConsumerWithContext",
      packageName + ".WrapperWithContext$FunctionWithContext",
      packageName + ".WrapperWithContext$SupplierOfCompletionStageWithContext",
      packageName + ".WrapperWithContext$SupplierWithContext",
      packageName + ".WrapperWithContext$SupplierOfFutureWithContext",
      packageName + ".WrapperWithContext$FinishOnGetFuture",
      packageName + ".Resilience4jSpanDecorator",
      packageName + ".Resilience4jSpan",
      packageName + ".CircuitBreakerDecorator",
      packageName + ".RetryDecorator",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new CircuitBreakerInstrumentation(),
        new FallbackCallableInstrumentation(),
        new FallbackCheckedSupplierInstrumentation(),
        new FallbackCompletionStageInstrumentation(),
        new FallbackSupplierInstrumentation(),
        new RetryInstrumentation());
  }
}
