package datadog.trace.core;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nonnull;

public class StreamingTraceCollector extends TraceCollector {

  static class Factory implements TraceCollector.Factory {
    private final CoreTracer tracer;
    private final TimeSource timeSource;
    private final HealthMetrics healthMetrics;

    Factory(CoreTracer tracer, TimeSource timeSource, HealthMetrics healthMetrics) {
      this.tracer = tracer;
      this.timeSource = timeSource;
      this.healthMetrics = healthMetrics;
    }

    @Override
    public StreamingTraceCollector create(@Nonnull DDTraceId traceId) {
      return create(traceId, null);
    }

    @Override
    public StreamingTraceCollector create(
        @Nonnull DDTraceId traceId, CoreTracer.ConfigSnapshot traceConfig) {
      return new StreamingTraceCollector(tracer, traceConfig, timeSource, healthMetrics);
    }
  }

  private final HealthMetrics healthMetrics;
  private volatile DDSpan rootSpan;

  private static final AtomicReferenceFieldUpdater<StreamingTraceCollector, DDSpan> ROOT_SPAN =
      AtomicReferenceFieldUpdater.newUpdater(
          StreamingTraceCollector.class, DDSpan.class, "rootSpan");

  private StreamingTraceCollector(
      CoreTracer tracer,
      CoreTracer.ConfigSnapshot traceConfig,
      TimeSource timeSource,
      HealthMetrics healthMetrics) {
    super(tracer, traceConfig != null ? traceConfig : tracer.captureTraceConfig(), timeSource);
    this.healthMetrics = healthMetrics;
  }

  @Override
  void touch() {
    // do nothing
  }

  @Override
  void registerSpan(DDSpan span) {
    ROOT_SPAN.compareAndSet(this, null, span);
    healthMetrics.onCreateSpan();
  }

  @Override
  DDSpan getRootSpan() {
    return rootSpan;
  }

  @Override
  PublishState onPublish(DDSpan span) {
    final DDSpan rootSpan = getRootSpan();
    if (span == rootSpan) {
      tracer.onRootSpanPublished(rootSpan);
    }
    healthMetrics.onFinishSpan();
    tracer.write(Collections.singletonList(span));
    return PublishState.WRITTEN;
  }

  @Override
  public void registerContinuation(AgentScope.Continuation continuation) {
    // do nothing
  }

  @Override
  public void removeContinuation(AgentScope.Continuation continuation) {
    // do nothing
  }
}
