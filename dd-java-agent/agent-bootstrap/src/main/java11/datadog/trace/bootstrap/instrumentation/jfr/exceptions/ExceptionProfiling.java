package datadog.trace.bootstrap.instrumentation.jfr.exceptions;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

/**
 * JVM-wide singleton exception profiling service. Uses {@linkplain Config} class to configure
 * itself using either system properties, environment or properties override.
 */
public final class ExceptionProfiling {

  /** Lazy initialization-on-demand. */
  private static final class Holder {
    static final ExceptionProfiling INSTANCE = new ExceptionProfiling(Config.get());
  }

  /**
   * Get a pre-configured shared instance.
   *
   * @return the shared instance
   */
  public static ExceptionProfiling getInstance() {
    return Holder.INSTANCE;
  }

  private final ExceptionHistogram histogram;
  private final ExceptionSampler sampler;

  private ExceptionProfiling(final Config config) {
    this(new ExceptionSampler(config), new ExceptionHistogram(config));
  }

  ExceptionProfiling(final ExceptionSampler sampler, final ExceptionHistogram histogram) {
    this.sampler = sampler;
    this.histogram = histogram;
  }

  public ExceptionSampleEvent process(final Throwable t) {
    // always record the exception in histogram
    final boolean firstHit = histogram.record(t);

    final boolean sampled = sampler.sample();
    if (firstHit || sampled) {
      long spanId = 0;
      long localRootSpanId = 0;
      AgentSpan activeSpan = AgentTracer.activeSpan();
      if (activeSpan != null) {
        spanId = activeSpan.getSpanId().toLong();
        AgentSpan rootSpan = activeSpan.getLocalRootSpan();
        localRootSpanId = rootSpan == null ? spanId : rootSpan.getSpanId().toLong();
      }
      return new ExceptionSampleEvent(t, sampled, firstHit, localRootSpanId, spanId);
    }
    return null;
  }
}
