package datadog.trace.core;

import static datadog.trace.api.DDTags.TRACE_START_TIME;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_END_TO_END_DURATION_MS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.profiling.TracingContextTracker;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import datadog.trace.api.profiling.TransientProfilingContextHolder;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.ToIntFunction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a period of time. Associated information is stored in the SpanContext.
 *
 * <p>Spans are created by the {@link CoreTracer#buildSpan}. This implementation adds some features
 * according to the DD agent.
 */
public class DDSpan
    implements AgentSpan, CoreSpan<DDSpan>, TransientProfilingContextHolder, AttachableWrapper {
  private static final Logger log = LoggerFactory.getLogger(DDSpan.class);

  public static final String CHECKPOINTED_TAG = "checkpointed";

  static DDSpan create(final long timestampMicro, @Nonnull DDSpanContext context) {
    final DDSpan span = new DDSpan(timestampMicro, context);
    log.debug("Started span: {}", span);
    context.getTrace().registerSpan(span);
    span.tracingContextTracker = TracingContextTrackerFactory.instance(span);
    return span;
  }

  /** The context attached to the span */
  private final DDSpanContext context;

  /** Is the source of time an external clock or our internal tick-adjusted clock? */
  private final boolean externalClock;

  /**
   * Creation time of span in nanoseconds. Must be greater than zero. For our internal clock we use
   * combination of millisecond-precision clock and nanosecond-precision offset from start of the
   * trace. See {@link PendingTrace} for details.
   */
  private final long startTimeNano;

  private static final AtomicLongFieldUpdater<DDSpan> DURATION_NANO_UPDATER =
      AtomicLongFieldUpdater.newUpdater(DDSpan.class, "durationNano");

  /**
   * The duration in nanoseconds computed using the startTimeMicro or startTimeNano.<hr> The span's
   * states are defined as follows:
   * <li>eq 0 -> unfinished.
   * <li>lt 0 -> finished but unpublished.
   * <li>gt 0 -> finished and published.
   */
  private volatile long durationNano;

  private boolean forceKeep;

  private volatile EndpointTracker endpointTracker;

  // Cached OT/OTel wrapper to avoid multiple allocations, e.g. when span is activated
  private volatile Object wrapper;
  private static final AtomicReferenceFieldUpdater<DDSpan, Object> WRAPPER_FIELD_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(DDSpan.class, Object.class, "wrapper");

  // the request is to be blocked (AppSec)
  private volatile Flow.Action.RequestBlockingAction requestBlockingAction;

  /**
   * Spans should be constructed using the builder, not by calling the constructor directly.
   *
   * @param timestampMicro if greater than zero, use this time instead of the current time
   * @param context the context used for the span
   */
  private DDSpan(final long timestampMicro, @Nonnull DDSpanContext context) {
    this.context = context;

    if (timestampMicro <= 0L) {
      // note: getting internal time from the trace implicitly 'touches' it
      startTimeNano = context.getTrace().getCurrentTimeNano();
      externalClock = false;
    } else {
      startTimeNano = MICROSECONDS.toNanos(timestampMicro);
      externalClock = true;
      context.getTrace().touch(); // external clock: explicitly update lastReferenced
    }
  }

  private volatile TracingContextTracker tracingContextTracker;
  // custom function to persist tracing context in the span tag with minimum number of extra
  // allocations
  private final ToIntFunction<ByteBuffer> tracingContextPersistor =
      new ToIntFunction<ByteBuffer>() {
        @Override
        public int applyAsInt(ByteBuffer byteBuffer) {
          String contextTagName = "_dd_tracing_context_" + tracingContextTracker.getVersion();
          UTF8BytesString encodedString =
              UTF8BytesString.create(Base64Encoder.INSTANCE.encode(byteBuffer));
          if (log.isTraceEnabled()) {
            log.trace(
                "Tracing context data for s_id:{}, tag:{}={}",
                getSpanId(),
                contextTagName,
                encodedString);
          }
          context.setTag(contextTagName, encodedString);
          return encodedString.encodedLength();
        }
      };

  public boolean isFinished() {
    return durationNano != 0;
  }

  private void finishAndAddToTrace(final long durationNano) {
    // ensure a min duration of 1
    if (DURATION_NANO_UPDATER.compareAndSet(this, 0, Math.max(1, durationNano))) {
      PendingTrace.PublishState publishState = context.getTrace().onPublish(this);
      log.debug("Finished span ({}): {}", publishState, this);
    } else {
      log.debug("Already finished: {}", this);
    }
  }

  @Override
  public final void finish() {
    if (!externalClock) {
      // no external clock was used, so we can rely on nano time
      finishAndAddToTrace(context.getTrace().getCurrentTimeNano() - startTimeNano);
    } else {
      finish(context.getTrace().getTimeSource().getCurrentTimeMicros());
    }
  }

  @Override
  public final void finish(final long stopTimeMicros) {
    long durationNano;
    if (!externalClock) {
      // first capture wall-clock offset from 'now' to external stop time
      long externalOffsetMicros =
          stopTimeMicros - context.getTrace().getTimeSource().getCurrentTimeMicros();
      // immediately afterwards calculate internal duration of span to 'now'
      // note: getting internal time from the trace implicitly 'touches' it
      durationNano = context.getTrace().getCurrentTimeNano() - startTimeNano;
      // drop nanosecond precision part of internal duration (expected behaviour)
      durationNano = MILLISECONDS.toNanos(NANOSECONDS.toMillis(durationNano));
      // add wall-clock offset to get total duration to external stop time
      durationNano += MICROSECONDS.toNanos(externalOffsetMicros);
    } else {
      durationNano = MICROSECONDS.toNanos(stopTimeMicros) - startTimeNano;
      context.getTrace().touch(); // external clock: explicitly update lastReferenced
    }
    finishAndAddToTrace(durationNano);
  }

  @Override
  public final void finishWithDuration(final long durationNano) {
    finishAndAddToTrace(durationNano);
  }

  private static final boolean legacyEndToEndEnabled =
      Config.get().isEndToEndDurationEnabled(false, "legacy");

  @Override
  public void beginEndToEnd() {
    if (legacyEndToEndEnabled) {
      if (null == getBaggageItem(TRACE_START_TIME)) {
        setBaggageItem(TRACE_START_TIME, Long.toString(NANOSECONDS.toMillis(startTimeNano)));
      }
    } else {
      context.beginEndToEnd();
    }
  }

  @Override
  public void finishWithEndToEnd() {
    long e2eStart;
    if (legacyEndToEndEnabled) {
      String value = context.getBaggageItem(TRACE_START_TIME);
      try {
        e2eStart = null != value ? MILLISECONDS.toNanos(Long.parseLong(value)) : 0;
      } catch (RuntimeException e) {
        log.debug("Ignoring invalid end-to-end start time {}", value, e);
        e2eStart = 0;
      }
    } else {
      e2eStart = context.getEndToEndStartTime();
    }
    if (e2eStart > 0) {
      phasedFinish();
      // get end time from start+duration, ignoring negative bit set by phasedFinish
      long e2eEnd = startTimeNano + (durationNano & Long.MAX_VALUE);
      setTag(RECORD_END_TO_END_DURATION_MS, NANOSECONDS.toMillis(Math.max(0, e2eEnd - e2eStart)));
      publish();
    } else {
      finish();
    }
  }

  @Override
  public final boolean phasedFinish() {
    long durationNano;
    if (!externalClock) {
      // note: getting internal time from the trace implicitly 'touches' it
      durationNano = context.getTrace().getCurrentTimeNano() - startTimeNano;
    } else {
      durationNano = context.getTrace().getTimeSource().getCurrentTimeNanos() - startTimeNano;
      context.getTrace().touch(); // external clock: explicitly update lastReferenced
    }
    // Flip the negative bit of the result to allow verifying that publish() is only called once.
    if (DURATION_NANO_UPDATER.compareAndSet(this, 0, Math.max(1, durationNano) | Long.MIN_VALUE)) {
      log.debug("Finished span (PHASED): {}", this);
      return true;
    } else {
      log.debug("Already finished: {}", this);
      return false;
    }
  }

  @Override
  public final void publish() {
    long durationNano = this.durationNano;
    if (durationNano == 0) {
      log.debug("Can't publish unfinished span: {}", this);
    } else if (durationNano > 0) {
      log.debug("Already published: {}", this);
    } else if (DURATION_NANO_UPDATER.compareAndSet(
        this, durationNano, durationNano & Long.MAX_VALUE)) {
      PendingTrace.PublishState publishState = context.getTrace().onPublish(this);
      log.debug("Published span ({}): {}", publishState, this);
    }
  }

  public int storeContextToTag() {
    try {
      return tracingContextTracker.persist(tracingContextPersistor);
    } catch (Throwable t) {
      log.error("", t);
    } finally {
      tracingContextTracker.release();
    }
    return -1;
  }

  @Override
  public DDSpan setError(final boolean error) {
    context.setErrorFlag(error);
    return this;
  }

  @Override
  public DDSpan setMeasured(boolean measured) {
    context.setMeasured(measured);
    return this;
  }

  public DDSpan forceKeep(boolean forceKeep) {
    this.forceKeep = forceKeep;
    return this;
  }

  @Override
  public boolean isForceKeep() {
    return forceKeep;
  }

  /**
   * Check if the span is the root parent. It means that the traceId is the same as the spanId. In
   * the context of distributed tracing this will return true if an only if this is the application
   * initializing the trace.
   *
   * @return true if root, false otherwise
   */
  public final boolean isRootSpan() {
    return context.getParentId() == DDSpanId.ZERO;
  }

  @Override
  @Deprecated
  public AgentSpan getRootSpan() {
    return getLocalRootSpan();
  }

  @Override
  public DDSpan getLocalRootSpan() {
    return context.getTrace().getRootSpan();
  }

  /**
   * Checks whether the span is also the local root span
   *
   * @return {@literal true} if this span is the same as {@linkplain #getLocalRootSpan()}
   */
  public boolean isLocalRootSpan() {
    return getLocalRootSpan().equals(this);
  }

  @Override
  public boolean isSameTrace(final AgentSpan otherSpan) {
    // FIXME [API] AgentSpan or AgentSpan.Context should have a "getTraceId()" type method
    if (otherSpan instanceof DDSpan) {
      return getTraceId().equals(otherSpan.getTraceId());
    }

    return false;
  }

  @Override
  public DDSpan setErrorMessage(final String errorMessage) {
    return setTag(DDTags.ERROR_MSG, errorMessage);
  }

  @Override
  public DDSpan addThrowable(final Throwable error) {
    if (null != error) {
      String message = error.getMessage();
      if (!"broken pipe".equalsIgnoreCase(message)
          && (error.getCause() == null
              || !"broken pipe".equalsIgnoreCase(error.getCause().getMessage()))) {
        // broken pipes happen when clients abort connections,
        // which might happen because the application is overloaded
        // or warming up - capturing the stack trace and keeping
        // the trace may exacerbate existing problems.
        setError(true);
        final StringWriter errorString = new StringWriter();
        error.printStackTrace(new PrintWriter(errorString));
        setTag(DDTags.ERROR_STACK, errorString.toString());
      }

      setTag(DDTags.ERROR_MSG, message);
      setTag(DDTags.ERROR_TYPE, error.getClass().getName());
    }

    return this;
  }

  @Override
  public final DDSpan setTag(final String tag, final String value) {
    context.setTag(tag, value);
    return this;
  }

  @Override
  public final DDSpan setTag(final String tag, final boolean value) {
    context.setTag(tag, value);
    return this;
  }

  @Override
  public void setRequestBlockingAction(Flow.Action.RequestBlockingAction rba) {
    this.requestBlockingAction = rba;
  }

  @Override
  public Flow.Action.RequestBlockingAction getRequestBlockingAction() {
    return requestBlockingAction;
  }

  @Override
  public DDSpan setTag(final String tag, final int value) {
    // can't use tag interceptor because it might set a metric
    // http.status is important because it is expected to be a string downstream
    if (HTTP_STATUS.equals(tag)) {
      context.setHttpStatusCode((short) value);
    }
    context.setTag(tag, value);
    return this;
  }

  @Override
  public DDSpan setTag(final String tag, final long value) {
    context.setTag(tag, value);
    return this;
  }

  @Override
  public DDSpan setTag(final String tag, final double value) {
    context.setTag(tag, value);
    return this;
  }

  @Override
  public DDSpan setTag(final String tag, final Number value) {
    context.setTag(tag, value);
    return this;
  }

  @Override
  public DDSpan setMetric(final CharSequence metric, final int value) {
    context.setMetric(metric, value);
    return this;
  }

  @Override
  public DDSpan setMetric(CharSequence name, float value) {
    context.setMetric(name, value);
    return this;
  }

  @Override
  public DDSpan setMetric(final CharSequence metric, final long value) {
    context.setMetric(metric, value);
    return this;
  }

  @Override
  public DDSpan setMetric(final CharSequence metric, final double value) {
    context.setMetric(metric, value);
    return this;
  }

  @Override
  public DDSpan setFlag(CharSequence name, boolean value) {
    context.setMetric(name, value ? 1 : 0);
    return this;
  }

  @Override
  public DDSpan setTag(final String tag, final CharSequence value) {
    context.setTag(tag, value);
    return this;
  }

  @Override
  public DDSpan setTag(final String tag, final Object value) {
    context.setTag(tag, value);
    return this;
  }

  // FIXME [API] this is not on AgentSpan or MutableSpan
  @Override
  public DDSpan removeTag(final String tag) {
    context.setTag(tag, null);
    return this;
  }

  @Override
  public Object getTag(final String tag) {
    return context.getTag(tag);
  }

  @Override
  @Nonnull
  public final DDSpanContext context() {
    return context;
  }

  @Override
  public final String getBaggageItem(final String key) {
    return context.getBaggageItem(key);
  }

  @Override
  public final DDSpan setBaggageItem(final String key, final String value) {
    context.setBaggageItem(key, value);
    return this;
  }

  @Override
  public AgentSpan setHttpStatusCode(int statusCode) {
    context.setHttpStatusCode((short) statusCode);
    return this;
  }

  @Override
  public short getHttpStatusCode() {
    return context.getHttpStatusCode();
  }

  @Override
  public CharSequence getOrigin() {
    return context.getOrigin();
  }

  @Override
  public final DDSpan setOperationName(final CharSequence operationName) {
    context.setOperationName(operationName);
    return this;
  }

  @Override
  public final DDSpan setServiceName(final String serviceName) {
    context.setServiceName(serviceName);
    return this;
  }

  @Override
  public final DDSpan setResourceName(final CharSequence resourceName) {
    return setResourceName(resourceName, ResourceNamePriorities.DEFAULT);
  }

  @Override
  public final DDSpan setResourceName(final CharSequence resourceName, byte priority) {
    context.setResourceName(resourceName, priority);
    return this;
  }

  @Override
  public boolean eligibleForDropping() {
    int samplingPriority = context.getSamplingPriority();
    return samplingPriority == USER_DROP || samplingPriority == SAMPLER_DROP;
  }

  @Override
  public void startWork() {
    if (tracingContextTracker != null) {
      tracingContextTracker.activateContext();
    }
  }

  @Override
  public void finishWork() {
    if (tracingContextTracker != null) {
      tracingContextTracker.deactivateContext();
    }
  }

  @Override
  public RequestContext getRequestContext() {
    return context.getRequestContext();
  }

  @Override
  public void mergePathwayContext(PathwayContext pathwayContext) {
    context.mergePathwayContext(pathwayContext);
  }

  @Deprecated
  @Override
  public final DDSpan setSamplingPriority(final int newPriority) {
    // this method exist only to satisfy MutableSpan. It will be removed in 1.0
    return setSamplingPriority(newPriority, SamplingMechanism.UNKNOWN);
  }

  /**
   * Set the sampling priority of the root span of this span's trace
   *
   * <p>Has no effect if the span priority has been propagated (injected or extracted).
   */
  @Override
  public final DDSpan setSamplingPriority(final int newPriority, int samplingMechanism) {
    context.setSamplingPriority(newPriority, samplingMechanism);
    return this;
  }

  @Override
  public DDSpan setSamplingPriority(
      int samplingPriority, CharSequence rate, double sampleRate, int samplingMechanism) {
    if (context.setSamplingPriority(samplingPriority, samplingMechanism)) {
      setMetric(rate, sampleRate);
    }
    return this;
  }

  @Override
  public DDSpan setSpanSamplingPriority(double rate, int limit) {
    context.setSpanSamplingPriority(rate, limit);
    return this;
  }

  @Override
  public final DDSpan setSpanType(final CharSequence type) {
    context.setSpanType(type);
    return this;
  }

  // Getters

  @Override
  public long getStartTime() {
    return startTimeNano;
  }

  @Override
  public long getDurationNano() {
    return durationNano;
  }

  @Override
  public String getServiceName() {
    return context.getServiceName();
  }

  @Override
  public DDTraceId getTraceId() {
    return context.getTraceId();
  }

  @Override
  public long getSpanId() {
    return context.getSpanId();
  }

  @Override
  public long getParentId() {
    return context.getParentId();
  }

  @Override
  public CharSequence getResourceName() {
    return context.getResourceName();
  }

  @Override
  public CharSequence getOperationName() {
    return context.getOperationName();
  }

  @Override
  public CharSequence getSpanName() {
    return context.getOperationName();
  }

  @Override
  public void setSpanName(final CharSequence spanName) {
    context.setOperationName(spanName);
  }

  @Override // TODO remove for 1.0: No usages within dd-trace-java
  public boolean hasResourceName() {
    return context.hasResourceName();
  }

  @Override
  public byte getResourceNamePriority() {
    return context.getResourceNamePriority();
  }

  @Override
  public Integer getSamplingPriority() {
    final int samplingPriority = context.getSamplingPriority();
    if (samplingPriority == PrioritySampling.UNSET) {
      return null;
    } else {
      return samplingPriority;
    }
  }

  @Override
  public int samplingPriority() {
    return context.getSamplingPriority();
  }

  @Override
  public String getSpanType() {
    final CharSequence spanType = context.getSpanType();
    return null == spanType ? null : spanType.toString();
  }

  @Override
  public Map<String, Object> getTags() {
    // This is an imutable copy of the tags
    return context.getTags();
  }

  @Override
  public CharSequence getType() {
    return context.getSpanType();
  }

  @Override
  public void processTagsAndBaggage(final MetadataConsumer consumer) {
    context.processTagsAndBaggage(consumer);
  }

  @Override
  public boolean isError() {
    return context.getErrorFlag();
  }

  @Override
  public int getError() {
    return context.getErrorFlag() ? 1 : 0;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <U> U getTag(CharSequence name, U defaultValue) {
    Object tag = getTag(String.valueOf(name));
    return null == tag ? defaultValue : (U) tag;
  }

  @Override
  public <U> U getTag(CharSequence name) {
    return getTag(name, null);
  }

  @Override
  public boolean hasSamplingPriority() {
    return context.getTrace().getRootSpan() == this;
  }

  @Override
  public boolean isMeasured() {
    return context.isMeasured();
  }

  @Override
  public boolean isTopLevel() {
    return context.isTopLevel();
  }

  /**
   * Retrieve the {@linkplain EndpointTracker} instance associated with the corresponding local root
   * span
   *
   * @return an {@linkplain EndpointTracker} instance or {@literal null}
   */
  @Nullable
  public EndpointTracker getEndpointTracker() {
    DDSpan localRootSpan = getLocalRootSpan();
    if (localRootSpan == null) {
      return null;
    }
    if (this.equals(localRootSpan)) {
      return endpointTracker;
    }
    return localRootSpan.endpointTracker;
  }

  /**
   * Attach an end-point tracker to the corresponding local root span. When this method is called
   * multiple times the last invocation will 'win'
   *
   * @param endpointTracker the end-point tracker instance
   */
  public void setEndpointTracker(@Nonnull EndpointTracker endpointTracker) {
    DDSpan localRootSpan = getLocalRootSpan();
    if (localRootSpan == null) {
      log.warn("Span {} has no associated local root span", this);
      return;
    }
    if (this.equals(localRootSpan)) {
      this.endpointTracker = endpointTracker;
    } else {
      localRootSpan.endpointTracker = endpointTracker;
    }
  }

  public Map<String, String> getBaggage() {
    return Collections.unmodifiableMap(context.getBaggageItems());
  }

  @Override
  public String toString() {
    return context.toString() + ", duration_ns=" + durationNano;
  }

  @Override
  public void attachWrapper(Object wrapper) {
    WRAPPER_FIELD_UPDATER.compareAndSet(this, null, wrapper);
  }

  @Override
  public Object getWrapper() {
    return WRAPPER_FIELD_UPDATER.get(this);
  }
}
