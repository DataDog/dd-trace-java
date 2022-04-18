package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDId;
import java.util.Collections;
import java.util.Map;

public class DummyLambdaContext implements AgentSpan.Context {

  private DDId traceID;
  private DDId spanID;

  public DummyLambdaContext(String traceID, String spanID) {
    this.traceID = DDId.from(traceID);
    this.spanID = DDId.from(spanID);
  }

  @Override
  public DDId getTraceId() {
    return this.traceID;
  }

  @Override
  public DDId getSpanId() {
    return this.spanID;
  }

  @Override
  public AgentTrace getTrace() {
    return AgentTracer.NoopAgentTrace.INSTANCE;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return Collections.emptyList();
  }

  @Override
  public PathwayContext getPathwayContext() {
    return null;
  }
}
