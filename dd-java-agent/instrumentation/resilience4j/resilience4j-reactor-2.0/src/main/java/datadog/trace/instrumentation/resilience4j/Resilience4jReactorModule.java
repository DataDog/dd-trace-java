package datadog.trace.instrumentation.resilience4j;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * This relies on the reactive-streams instrumentation. It attaches its span to the publisher. Then,
 * the reactive-streams instrumentation activates the span downstream.
 */
@AutoService(InstrumenterModule.class)
public class Resilience4jReactorModule extends InstrumenterModule.Tracing {

  public Resilience4jReactorModule() {
    super("resilience4j-reactor");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".Resilience4jSpan",
      packageName + ".Resilience4jSpanDecorator",
      packageName + ".CircuitBreakerDecorator",
      packageName + ".RetryDecorator",
      packageName + ".ReactorHelper",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("org.reactivestreams.Publisher", Context.class.getName());
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new CircuitBreakerOperatorInstrumentation(),
        new FallbackOperatorInstrumentation(),
        new RetryOperatorInstrumentation());
  }
}
