package datadog.trace.instrumentation.lettuce5.rx;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.function.Consumer;

public final class LettuceFlowTracker<R> implements Consumer<R> {
  public final AgentSpan flowSpan;

  public LettuceFlowTracker(AgentSpan flowSpan) {
    this.flowSpan = flowSpan;
  }

  @Override
  public void accept(R r) {
    if (flowSpan != null) {
      AgentSpan previousSpan = AgentTracer.activeSpan();
      if (flowSpan != previousSpan) {
        AgentTracer.activateSpan(flowSpan);
      }
    }
  }
}
