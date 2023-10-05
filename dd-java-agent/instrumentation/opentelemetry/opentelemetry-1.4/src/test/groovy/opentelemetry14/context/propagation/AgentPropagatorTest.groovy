package opentelemetry14.context.propagation

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.propagation.TextMapPropagator

abstract class AgentPropagatorTest extends AbstractPropagatorTest {
  @Override
  TextMapPropagator propagator() {
    // Get agent propagator injected by instrumentation
    return GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
  }
}
