package datadog.trace.instrumentation.spark;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;

public class PredeterminedTraceIdContext implements AgentSpanContext {
  private final DDTraceId traceId;

  public PredeterminedTraceIdContext(DDTraceId traceId) {
    this.traceId = traceId;
  }

  @Override
  public DDTraceId getTraceId() {
    return this.traceId;
  }

  @Override
  public long getSpanId() {
    return 0;
  }

  @Override
  public AgentTraceCollector getTraceCollector() {
    return AgentTracer.NoopAgentTraceCollector.INSTANCE;
  }

  @Override
  public int getSamplingPriority() {
    return PrioritySampling.USER_KEEP;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return null;
  }

  @Override
  public PathwayContext getPathwayContext() {
    return null;
  }

  @Override
  public boolean isRemote() {
    return false;
  }
}
