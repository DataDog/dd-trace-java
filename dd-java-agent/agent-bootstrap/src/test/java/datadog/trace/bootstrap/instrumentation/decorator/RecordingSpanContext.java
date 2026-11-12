package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import java.util.Collections;
import java.util.Map;

/**
 * A recording {@link AgentSpanContext} test double for decorator {@code afterStart} tests. Captures
 * the integration name applied by {@link datadog.trace.bootstrap.instrumentation.decorator}
 * decorators; every other accessor returns an inert default.
 */
final class RecordingSpanContext implements AgentSpanContext {
  private CharSequence integrationName;

  @Override
  public void setIntegrationName(CharSequence componentName) {
    this.integrationName = componentName;
  }

  CharSequence recordedIntegrationName() {
    return integrationName;
  }

  @Override
  public DDTraceId getTraceId() {
    return DDTraceId.ZERO;
  }

  @Override
  public long getSpanId() {
    return 0;
  }

  @Override
  public AgentTraceCollector getTraceCollector() {
    return null;
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
    return null;
  }

  @Override
  public boolean isRemote() {
    return false;
  }
}
