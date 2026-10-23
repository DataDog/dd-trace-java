package datadog.trace.core;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentTraceCollector;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import datadog.trace.common.sampling.PrioritySampler;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.util.TagsHelper;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import javax.annotation.Nonnull;

/**
 * The local part of a trace: the spans of one trace within this tracer. Holds trace-level state
 * shared by those spans, and is their {@link TraceSegment} and {@link RequestContext}.
 */
public abstract class TraceCollector implements AgentTraceCollector, TraceSegment, RequestContext {

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

  private volatile Object requestContextDataAppSec;
  private static final AtomicReferenceFieldUpdater<TraceCollector, Object> REQUEST_DATA_APPSEC =
      AtomicReferenceFieldUpdater.newUpdater(
          TraceCollector.class, Object.class, "requestContextDataAppSec");

  private volatile Object requestContextDataIast;
  private static final AtomicReferenceFieldUpdater<TraceCollector, Object> REQUEST_DATA_IAST =
      AtomicReferenceFieldUpdater.newUpdater(
          TraceCollector.class, Object.class, "requestContextDataIast");

  private volatile Object ciVisibilityContextData;
  private static final AtomicReferenceFieldUpdater<TraceCollector, Object> CI_VISIBILITY_DATA =
      AtomicReferenceFieldUpdater.newUpdater(
          TraceCollector.class, Object.class, "ciVisibilityContextData");
  private volatile PropagationTags propagationTags;
  private static final AtomicReferenceFieldUpdater<TraceCollector, PropagationTags>
      PROPAGATION_TAGS =
          AtomicReferenceFieldUpdater.newUpdater(
              TraceCollector.class, PropagationTags.class, "propagationTags");

  /** The origin of the trace. (eg. Synthetics, CI App) */
  private volatile CharSequence origin;

  private volatile BlockResponseFunction blockResponseFunction;
  private volatile ClientIpAddressData clientIpAddressData;

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
      // Skip sampler override when _dd.p.ts is marked for ASM or AI Guard.
      if ((!Config.get().isApmTracingEnabled()
              && !ProductTraceSource.isProductMarked(
                  rootSpan.spanContext().getPropagationTags().getTraceSource(),
                  ProductTraceSource.ASM,
                  ProductTraceSource.AI_GUARD))
          || rootSpan.spanContext().getSamplingPriority() == PrioritySampling.UNSET) {
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

  /** The trace's propagation tags, or null if no span has supplied them yet. */
  PropagationTags getPropagationTagsIfPresent() {
    return propagationTags;
  }

  /**
   * Sets the trace's propagation tags unless already set.
   *
   * @return the trace's propagation tags, which may not be {@code propagationTags}
   */
  PropagationTags initPropagationTags(PropagationTags propagationTags) {
    return PROPAGATION_TAGS.compareAndSet(this, null, propagationTags)
        ? propagationTags
        : this.propagationTags;
  }

  CharSequence getOrigin() {
    return origin;
  }

  void setOrigin(CharSequence origin) {
    this.origin = origin;
  }

  /**
   * Records the request-scoped product data the trace was started with. Only the first non-null
   * value for each slot is kept.
   */
  void initRequestContextData(
      Object requestContextDataAppSec,
      Object requestContextDataIast,
      Object ciVisibilityContextData) {
    if (requestContextDataAppSec != null) {
      REQUEST_DATA_APPSEC.compareAndSet(this, null, requestContextDataAppSec);
    }
    if (requestContextDataIast != null) {
      REQUEST_DATA_IAST.compareAndSet(this, null, requestContextDataIast);
    }
    if (ciVisibilityContextData != null) {
      CI_VISIBILITY_DATA.compareAndSet(this, null, ciVisibilityContextData);
    }
  }

  /** RequestContext Implementation */
  @SuppressWarnings("unchecked")
  @Override
  public <T> T getData(RequestContextSlot slot) {
    if (slot == RequestContextSlot.APPSEC) {
      return (T) requestContextDataAppSec;
    } else if (slot == RequestContextSlot.CI_VISIBILITY) {
      return (T) ciVisibilityContextData;
    } else if (slot == RequestContextSlot.IAST) {
      return (T) requestContextDataIast;
    }
    return null;
  }

  @Override
  public TraceSegment getTraceSegment() {
    return this;
  }

  @Override
  public void setBlockResponseFunction(BlockResponseFunction blockResponseFunction) {
    this.blockResponseFunction = blockResponseFunction;
  }

  @Override
  public BlockResponseFunction getBlockResponseFunction() {
    return blockResponseFunction;
  }

  @Override
  public void setClientIpAddressData(ClientIpAddressData clientIpAddressData) {
    this.clientIpAddressData = clientIpAddressData;
  }

  @Override
  public ClientIpAddressData getClientIpAddressData() {
    return clientIpAddressData;
  }

  @Override
  public <T> T getOrCreateMetaStructTop(String key, Function<String, T> defaultValue) {
    DDSpanContext top = rootSpanContext();
    return top != null ? top.getOrCreateMetaStruct(key, defaultValue) : null;
  }

  @Override
  public void close() throws IOException {
    Exception exc = null;
    if (requestContextDataAppSec instanceof Closeable) {
      try {
        ((Closeable) requestContextDataAppSec).close();
      } catch (IOException | RuntimeException e) {
        exc = e;
      }
    }
    if (requestContextDataIast instanceof Closeable) {
      try {
        ((Closeable) requestContextDataIast).close();
      } catch (IOException | RuntimeException e) {
        exc = e;
      }
    }
    if (exc != null) {
      if (exc instanceof RuntimeException) {
        throw (RuntimeException) exc;
      } else {
        throw (IOException) exc;
      }
    }
  }

  /** TraceSegment Implementation */
  @Override
  public void setTagTop(String key, Object value, boolean sanitize) {
    DDSpanContext top = rootSpanContext();
    if (top != null) {
      top.setTag(sanitize ? TagsHelper.sanitize(key) : key, value);
    }
  }

  @Override
  public Object getTagTop(String key, boolean sanitize) {
    DDSpanContext top = rootSpanContext();
    return top != null ? top.getTag(sanitize ? TagsHelper.sanitize(key) : key) : null;
  }

  @Override
  public void setDataTop(String key, Object value) {
    DDSpanContext top = rootSpanContext();
    if (top != null) {
      top.setTag(dataTagName(key), value);
    }
  }

  @Override
  public Object getDataTop(String key) {
    DDSpanContext top = rootSpanContext();
    return top != null ? top.getTag(dataTagName(key)) : null;
  }

  @Override
  public void effectivelyBlocked() {
    setTagTop("appsec.blocked", "true");
  }

  @Override
  public void setMetaStructTop(String field, Object value) {
    DDSpanContext top = rootSpanContext();
    if (top != null) {
      top.setMetaStruct(field, value);
    }
  }

  private static String dataTagName(String key) {
    // TODO is this decided?
    return "_dd." + key + ".json";
  }

  /** The local root span's context, or null before the first span registers. */
  private DDSpanContext rootSpanContext() {
    DDSpan rootSpan = getRootSpan();
    return rootSpan != null ? rootSpan.spanContext() : null;
  }
}
