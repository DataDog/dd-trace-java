package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import java.util.Map;

/** An {@link AgentSpanContext} that hides the sampling priority. */
public final class NotSampledSpanContext implements AgentSpanContext {
  private final AgentSpanContext delegate;

  public NotSampledSpanContext(AgentSpanContext delegate) {
    this.delegate = delegate;
  }

  @Override
  public DDTraceId getTraceId() {
    return delegate.getTraceId();
  }

  @Override
  public long getSpanId() {
    return delegate.getSpanId();
  }

  @Override
  public AgentTraceCollector getTraceCollector() {
    return delegate.getTraceCollector();
  }

  @Override
  public int getSamplingPriority() {
    return PrioritySampling.UNSET;
  }

  @Override
  public Iterable<Map.Entry<String, String>> baggageItems() {
    return delegate.baggageItems();
  }

  @Override
  public PathwayContext getPathwayContext() {
    return delegate.getPathwayContext();
  }

  @Override
  public boolean isRemote() {
    return delegate.isRemote();
  }
}
