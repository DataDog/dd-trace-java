package datadog.opentelemetry.shim.context.propagation;

import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;

public class OtelContextPropagators implements ContextPropagators {
  public static final ContextPropagators INSTANCE = new OtelContextPropagators();
  private final TextMapPropagator propagator = new AgentTextMapPropagator();

  @Override
  public TextMapPropagator getTextMapPropagator() {
    return this.propagator;
  }
}
