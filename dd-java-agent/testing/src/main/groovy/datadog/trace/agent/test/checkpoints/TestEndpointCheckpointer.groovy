package datadog.trace.agent.test.checkpoints

import datadog.trace.api.EndpointCheckpointer
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class TestEndpointCheckpointer implements EndpointCheckpointer {

  void onRootSpanFinished(AgentSpan rootSpan, boolean published) {
  }

  void onRootSpanStarted(AgentSpan rootSpan) {}
}
