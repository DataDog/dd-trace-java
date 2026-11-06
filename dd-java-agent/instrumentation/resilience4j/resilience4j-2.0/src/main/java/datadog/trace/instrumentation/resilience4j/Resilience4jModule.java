package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class Resilience4jModule extends InstrumenterModule.Tracing
    implements ExcludeFilterProvider {

  public Resilience4jModule() {
    super("resilience4j");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(
        COMPLETABLE_FUTURE_CALLBACK,
        singleton(packageName + ".WrapperWithContext$SpanFinishingCallback"));
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
