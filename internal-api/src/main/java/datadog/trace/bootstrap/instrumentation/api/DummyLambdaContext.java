package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.DDId;
import java.util.Collections;
import java.util.Map;

public class DummyLambdaContext implements AgentSpan.Context {

  private DDId traceID;
  private DDId spanID;
  private int samplingPriority = PrioritySampling.UNSET;

  public DummyLambdaContext(final String traceID, final String spanID, final String samplingPriority) {
    this.traceID = DDId.from(traceID);
    this.spanID = DDId.from(spanID);
    if (null != samplingPriority) {
      try {
        this.samplingPriority = Integer.parseInt(samplingPriority);
      } catch (final NumberFormatException ignored) {
        this.samplingPriority = PrioritySampling.UNSET;
      }
    }
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

  public int getSamplingPriority() {
    return samplingPriority;
  }
}
