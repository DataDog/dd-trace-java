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
