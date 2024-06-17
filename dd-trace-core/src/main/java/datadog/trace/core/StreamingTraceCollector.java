package datadog.trace.core;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nonnull;

public class StreamingTraceCollector extends TraceCollector {

  // FIXME nikita: see if healthMetrics can be used

  static class Factory implements TraceCollector.Factory {
    private final CoreTracer tracer;
    private final TimeSource timeSource;

    Factory(CoreTracer tracer, TimeSource timeSource) {
      this.tracer = tracer;
      this.timeSource = timeSource;
    }

    @Override
    public StreamingTraceCollector create(@Nonnull DDTraceId traceId) {
      return create(traceId, null);
    }

    @Override
    public StreamingTraceCollector create(
        @Nonnull DDTraceId traceId, CoreTracer.ConfigSnapshot traceConfig) {
      return new StreamingTraceCollector(
          tracer, traceConfig != null ? traceConfig : tracer.captureTraceConfig(), timeSource);
    }
  }

  private volatile DDSpan rootSpan;

  private static final AtomicReferenceFieldUpdater<StreamingTraceCollector, DDSpan> ROOT_SPAN =
      AtomicReferenceFieldUpdater.newUpdater(
          StreamingTraceCollector.class, DDSpan.class, "rootSpan");

  protected StreamingTraceCollector(
      CoreTracer tracer, CoreTracer.ConfigSnapshot traceConfig, TimeSource timeSource) {
    super(tracer, traceConfig, timeSource);
  }

  @Override
  void touch() {
    // do nothing
  }

  @Override
  void registerSpan(DDSpan span) {
    ROOT_SPAN.compareAndSet(this, null, span);
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
    tracer.write(Collections.singletonList(span));
    return PublishState.WRITTEN;
  }

  @Override
  public void registerContinuation(AgentScope.Continuation continuation) {
    // do nothing
  }

  @Override
  public void cancelContinuation(AgentScope.Continuation continuation) {
    // do nothing
  }
}
