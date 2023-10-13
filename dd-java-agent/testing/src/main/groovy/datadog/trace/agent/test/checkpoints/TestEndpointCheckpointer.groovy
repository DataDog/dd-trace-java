package datadog.trace.agent.test.checkpoints

import datadog.trace.api.EndpointCheckpointer
import datadog.trace.api.EndpointTracker
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

class TestEndpointCheckpointer implements EndpointCheckpointer {


  @Override
  void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {
  }

  @Override
  EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
    return EndpointTracker.NO_OP
  }
}
