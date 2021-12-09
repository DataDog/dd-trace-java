package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.RECORD_END_TO_END_DURATION_MS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.DDId;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.core.util.Clock;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
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
public class DDSpan implements AgentSpan, CoreSpan<DDSpan> {
  private static final Logger log = LoggerFactory.getLogger(DDSpan.class);

  public static final String CHECKPOINTED_TAG = "checkpointed";

  static DDSpan create(final long timestampMicro, @Nonnull DDSpanContext context) {
    return create(timestampMicro, context, true);
  }

  static DDSpan create(
      final long timestampMicro, @Nonnull DDSpanContext context, boolean emitCheckpoints) {
    final DDSpan span = new DDSpan(timestampMicro, context, emitCheckpoints);
    log.debug("Started span: {}", span);
    context.getTrace().registerSpan(span);
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

  // Marked as volatile to assure proper publication to child spans executed on different threads
  private volatile byte emittingCheckpoints; // 0 = unset, 1 = true, -1 = false

  private final boolean withCheckpoints;

  private volatile EndpointTracker endpointTracker;

  /**
   * Spans should be constructed using the builder, not by calling the constructor directly.
   *
   * @param timestampMicro if greater than zero, use this time instead of the current time
   * @param context the context used for the span
   */
  private DDSpan(final long timestampMicro, @Nonnull DDSpanContext context) {
    this(timestampMicro, context, true);
  }

  private DDSpan(
      final long timestampMicro, @Nonnull DDSpanContext context, boolean emitLocalCheckpoints) {
    this.context = context;
    this.withCheckpoints = emitLocalCheckpoints;

    if (timestampMicro <= 0L) {
      // capture internal time
      startTimeNano = context.getTrace().getCurrentTimeNano();
      externalClock = false;
    } else {
      startTimeNano = MICROSECONDS.toNanos(timestampMicro);
      externalClock = true;
      context.getTrace().touch(); // Update lastReferenced
    }
  }

  public boolean isFinished() {
    return durationNano != 0;
  }

  private void finishAndAddToTrace(final long durationNano) {
    // ensure a min duration of 1
    if (DURATION_NANO_UPDATER.compareAndSet(this, 0, Math.max(1, durationNano))) {
      context.getTrace().onFinish(this);
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
      finish(Clock.currentMicroTime());
    }
  }

  @Override
  public final void finish(final long stopTimeMicros) {
    context.getTrace().touch(); // Update timestamp
    long adjustedStartTimeNano;
    if (!externalClock) {
      // remove tick precision part of our internal time to better match external clock
      adjustedStartTimeNano = MILLISECONDS.toNanos(NANOSECONDS.toMillis(startTimeNano));
    } else {
      adjustedStartTimeNano = startTimeNano;
    }
    finishAndAddToTrace(MICROSECONDS.toNanos(stopTimeMicros) - adjustedStartTimeNano);
  }

  @Override
  public void beginEndToEnd() {
    context.beginEndToEnd();
  }

  @Override
  public void finishEndToEnd() {
    long recordStartNano = context.getEndToEndStartTime();
    if (recordStartNano > 0) {
      phasedFinish();
      // get end time from start+duration, ignoring negative bit set by phasedFinish
      long recordEndNano = startTimeNano + (durationNano & Long.MAX_VALUE);
      setTag(RECORD_END_TO_END_DURATION_MS, NANOSECONDS.toMillis(recordEndNano - recordStartNano));
      publish();
    } else {
      finish();
    }
  }

  @Override
  public final boolean phasedFinish() {
    long durationNano;
    if (!externalClock) {
      durationNano = context.getTrace().getCurrentTimeNano() - startTimeNano;
    } else {
      durationNano = MICROSECONDS.toNanos(Clock.currentMicroTime()) - startTimeNano;
    }
    // Flip the negative bit of the result to allow verifying that publish() is only called once.
    if (DURATION_NANO_UPDATER.compareAndSet(this, 0, Math.max(1, durationNano) | Long.MIN_VALUE)) {
      context.getTrace().onFinish(this);
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

  @Override
  public void setEmittingCheckpoints(boolean value) {
    /*
    The decision to emit checkpoints is made at the local root span level.
    This is to ensure consistency in the emitted checkpoints where the whole
    local root span subtree must either be fully covered or no checkpoints should
    be emitted at all.
    */
    DDSpan rootSpan = getLocalRootSpan();
    if (rootSpan.emittingCheckpoints == 0) {
      rootSpan.emittingCheckpoints = value ? 1 : (byte) -1;
      if (value) {
        rootSpan.setTag(CHECKPOINTED_TAG, value);
      }
    }
  }

  @Override
  public Boolean isEmittingCheckpoints() {
    /*
    The decision to emit checkpoints is made at the local root span level.
    This is to ensure consistency in the emitted checkpoints where the whole
    local root span subtree must either be fully covered or no checkpoints should
    be emitted at all.
    */
    byte flag = getLocalRootSpan().emittingCheckpoints;
    return flag == 0 ? null : flag > 0 ? Boolean.TRUE : Boolean.FALSE;
  }

  @Override
  public boolean hasCheckpoints() {
    return withCheckpoints;
  }

  /**
   * Check if the span is the root parent. It means that the traceId is the same as the spanId. In
   * the context of distributed tracing this will return true if an only if this is the application
   * initializing the trace.
   *
   * @return true if root, false otherwise
   */
  public final boolean isRootSpan() {
    return DDId.ZERO.equals(context.getParentId());
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
      // minor optimization to avoid BigInteger.toString()
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
      if (!"broken pipe".equalsIgnoreCase(message)) {
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
  public void startThreadMigration() {
    if (hasCheckpoints()) {
      context.getTracer().onStartThreadMigration(this);
    }
  }

  @Override
  public void finishThreadMigration() {
    if (hasCheckpoints()) {
      context.getTracer().onFinishThreadMigration(this);
    }
  }

  @Override
  public void finishWork() {
    if (hasCheckpoints()) {
      context.getTracer().onFinishWork(this);
    }
  }

  @Override
  public RequestContext<Object> getRequestContext() {
    return context.getRequestContext();
  }

  /**
   * Set the sampling priority of the root span of this span's trace
   *
   * <p>Has no effect if the span priority has been propagated (injected or extracted).
   */
  @Override
  public final DDSpan setSamplingPriority(final int newPriority) {
    context.setSamplingPriority(newPriority);
    return this;
  }

  @Override
  public DDSpan setSamplingPriority(int samplingPriority, CharSequence rate, double sampleRate) {
    if (context.setSamplingPriority(samplingPriority)) {
      setMetric(rate, sampleRate);
    }
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
  public DDId getTraceId() {
    return context.getTraceId();
  }

  @Override
  public DDId getSpanId() {
    return context.getSpanId();
  }

  @Override
  public DDId getParentId() {
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
}
