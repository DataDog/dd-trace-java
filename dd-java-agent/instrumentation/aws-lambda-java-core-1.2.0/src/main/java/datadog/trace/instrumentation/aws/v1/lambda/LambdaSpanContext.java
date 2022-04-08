package datadog.trace.instrumentation.aws.v1.lambda;
import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

import java.util.Collections;
import java.util.Map;

public class LambdaSpanContext implements AgentSpan.Context {

  private DDId traceID;
  private DDId spanID;

  public LambdaSpanContext(String traceID, String spanID) {
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

}
