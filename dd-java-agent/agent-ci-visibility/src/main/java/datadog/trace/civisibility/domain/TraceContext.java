package datadog.trace.civisibility.domain;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import java.util.Collections;
import java.util.Map;

/**
 * Test session spans need to have trace ID and span ID which are identical (CI Test Cycle protocol
 * requirement), so this dummy context class is used as a crutch to supply a specific trace ID when
 * creating a session span.
 */
public class TraceContext implements AgentSpan.Context {

  private final DDTraceId traceId;

  public TraceContext(DDTraceId traceId) {
    this.traceId = traceId;
  }

  @Override
  public DDTraceId getTraceId() {
    return traceId;
  }

  @Override
  public long getSpanId() {
    return DDSpanId.ZERO;
  }

  @Override
  public AgentTraceCollector getTraceCollector() {
    return AgentTracer.NoopAgentTraceCollector.INSTANCE;
  }

  @Override
  public int getSamplingPriority() {
    return PrioritySampling.UNSET;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return Collections.emptyList();
  }

  @Override
  public PathwayContext getPathwayContext() {
    return AgentTracer.NoopPathwayContext.INSTANCE;
  }
}
