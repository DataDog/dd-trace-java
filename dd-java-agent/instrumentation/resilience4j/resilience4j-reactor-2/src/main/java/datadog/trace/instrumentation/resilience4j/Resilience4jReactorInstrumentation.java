package datadog.trace.instrumentation.resilience4j;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;

/**
 * The resilience4j-reactor instrumentations rely on the reactive-streams instrumentation. It
 * attaches a r4j span to the publisher, which is then activated downstream.
 */
public abstract class Resilience4jReactorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public Resilience4jReactorInstrumentation(String... additionalNames) {
    super("resilience4j-reactor", additionalNames);
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
    final Map<String, String> ret = new HashMap<>();
    ret.put("org.reactivestreams.Publisher", AgentSpan.class.getName());
    return ret;
  }
}
