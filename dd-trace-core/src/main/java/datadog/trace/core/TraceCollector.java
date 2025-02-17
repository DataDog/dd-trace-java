package datadog.trace.core;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import datadog.trace.common.sampling.PrioritySampler;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import javax.annotation.Nonnull;

public abstract class TraceCollector implements AgentTraceCollector {

  interface Factory {
    /** Used by tests and benchmarks. */
    TraceCollector create(@Nonnull DDTraceId traceId);

    TraceCollector create(@Nonnull DDTraceId traceId, CoreTracer.ConfigSnapshot traceConfig);
  }

  enum PublishState {
    WRITTEN,
    PARTIAL_FLUSH,
    ROOT_BUFFERED,
    BUFFERED,
    PENDING
  }

  protected final CoreTracer tracer;
  protected final CoreTracer.ConfigSnapshot traceConfig;
  protected final TimeSource timeSource;

  private volatile long endToEndStartTime;
  private static final AtomicLongFieldUpdater<TraceCollector> END_TO_END_START_TIME =
      AtomicLongFieldUpdater.newUpdater(TraceCollector.class, "endToEndStartTime");

  protected TraceCollector(
      CoreTracer tracer, CoreTracer.ConfigSnapshot traceConfig, TimeSource timeSource) {
    this.tracer = tracer;
    this.traceConfig = traceConfig;
    this.timeSource = timeSource;
  }

  CoreTracer getTracer() {
    return tracer;
  }

  CoreTracer.ConfigSnapshot getTraceConfig() {
    return traceConfig;
  }

  String mapServiceName(String serviceName) {
    return traceConfig.getServiceMapping().getOrDefault(serviceName, serviceName);
  }

  boolean sample(DDSpan spanToSample) {
    return traceConfig.sampler.sample(spanToSample);
  }

  public void setSamplingPriorityIfNecessary() {
    // There's a race where multiple threads can see PrioritySampling.UNSET here
    // This check skips potential complex sampling priority logic when we know its redundant
    // Locks inside DDSpanContext ensure the correct behavior in the race case
    DDSpan rootSpan = getRootSpan();
    if (traceConfig.sampler instanceof PrioritySampler && rootSpan != null) {
      // Ignore the force-keep priority in the absence of propagated _dd.p.ts span tag marked for
      // ASM.
      if ((!Config.get().isApmTracingEnabled()
              && !ProductTraceSource.isProductMarked(
                  rootSpan.context().getPropagationTags().getTraceSource(), ProductTraceSource.ASM))
          || rootSpan.context().getSamplingPriority() == PrioritySampling.UNSET) {
        ((PrioritySampler) traceConfig.sampler).setSamplingPriority(rootSpan);
      }
    }
  }

  public TimeSource getTimeSource() {
    return timeSource;
  }

  public long getCurrentTimeNano() {
    long nanoTicks = timeSource.getNanoTicks();
    return tracer.getTimeWithNanoTicks(nanoTicks);
  }

  void beginEndToEnd() {
    beginEndToEnd(getCurrentTimeNano());
  }

  void beginEndToEnd(long endToEndStartTime) {
    END_TO_END_START_TIME.compareAndSet(this, 0, endToEndStartTime);
  }

  long getEndToEndStartTime() {
    return endToEndStartTime;
  }

  abstract void touch();

  abstract void registerSpan(final DDSpan span);

  abstract DDSpan getRootSpan();

  abstract PublishState onPublish(final DDSpan span);
}
