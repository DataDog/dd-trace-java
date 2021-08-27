package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;

import datadog.trace.api.DDId;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.util.Clock;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
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
    final DDSpan span = new DDSpan(timestampMicro, context);
    log.debug("Started span: {}", span);
    context.getTrace().registerSpan(span);
    return span;
  }

  /** The context attached to the span */
  private final DDSpanContext context;

  /**
   * Creation time of the span in microseconds provided by external clock. Must be greater than
   * zero.
   */
  private final long startTimeMicro;

  /**
   * Creation time of span in nanoseconds. We use combination of millisecond-precision clock and
   * nanosecond-precision offset from start of the trace. See {@link PendingTrace} for details. Must
   * be greater than zero.
   */
  private final long startTimeNano;

  /**
   * The duration in nanoseconds computed using the startTimeMicro or startTimeNano.<hr> The span's
   * states are defined as follows:
   * <li>eq 0 -> unfinished.
   * <li>lt 0 -> finished but unpublished.
   * <li>gt 0 -> finished and published.
   */
  private final AtomicLong durationNano = new AtomicLong();

  private boolean forceKeep;

  // Marked as volatile to assure proper publication to child spans executed on different threads
  volatile Boolean emittingCheckpoints = null;

  /**
   * Spans should be constructed using the builder, not by calling the constructor directly.
   *
   * @param timestampMicro if greater than zero, use this time instead of the current time
   * @param context the context used for the span
   */
  private DDSpan(final long timestampMicro, @Nonnull DDSpanContext context) {
    this.context = context;

    if (timestampMicro <= 0L) {
      // record the start time
      startTimeMicro = Clock.currentMicroTime();
      startTimeNano = context.getTrace().getCurrentTimeNano();
    } else {
      startTimeMicro = timestampMicro;
      // Timestamp has come from an external clock, so use startTimeNano as a flag
      startTimeNano = 0;
      context.getTrace().touch(); // Update lastReferenced
    }
  }

  public boolean isFinished() {
    return durationNano.get() != 0;
  }

  private void finishAndAddToTrace(final long durationNano) {
    // ensure a min duration of 1
    if (this.durationNano.compareAndSet(0, Math.max(1, durationNano))) {
      context.getTrace().onFinish(this);
      PendingTrace.PublishState publishState = context.getTrace().onPublish(this);
      log.debug("Finished span ({}): {}", publishState, this);
    } else {
      log.debug("Already finished: {}", this);
    }
  }

  @Override
  public final void finish() {
    if (startTimeNano > 0) {
      // no external clock was used, so we can rely on nano time
      finishAndAddToTrace(context.getTrace().getCurrentTimeNano() - startTimeNano);
    } else {
      finish(Clock.currentMicroTime());
    }
  }

  @Override
  public final void finish(final long stoptimeMicros) {
    context.getTrace().touch(); // Update timestamp
    finishAndAddToTrace(TimeUnit.MICROSECONDS.toNanos(stoptimeMicros - startTimeMicro));
  }

  @Override
  public final boolean phasedFinish() {
    long durationNano;
    if (startTimeNano > 0) {
      durationNano = context.getTrace().getCurrentTimeNano() - startTimeNano;
    } else {
      durationNano = TimeUnit.MICROSECONDS.toNanos(Clock.currentMicroTime() - startTimeMicro);
    }
    // Flip the negative bit of the result to allow verifying that publish() is only called once.
    if (this.durationNano.compareAndSet(0, Math.max(1, durationNano) | Long.MIN_VALUE)) {
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
    long durationNano = this.durationNano.get();
    if (durationNano == 0) {
      log.debug("Can't publish unfinished span: {}", this);
    } else if (durationNano > 0) {
      log.debug("Already published: {}", this);
    } else if (this.durationNano.compareAndSet(durationNano, durationNano & Long.MAX_VALUE)) {
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
    if (rootSpan.emittingCheckpoints == null) {
      rootSpan.emittingCheckpoints = value;
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
    return getLocalRootSpan().emittingCheckpoints;
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
    context.setResourceName(resourceName);
    return this;
  }

  @Override
  public boolean eligibleForDropping() {
    int samplingPriority = context.getSamplingPriority();
    return samplingPriority == USER_DROP || samplingPriority == SAMPLER_DROP;
  }

  @Override
  public void startThreadMigration() {
    context.getTracer().onStartThreadMigration(this);
  }

  @Override
  public void finishThreadMigration() {
    context.getTracer().onFinishThreadMigration(this);
  }

  @Override
  public void finishWork() {
    context.getTracer().onFinishWork(this);
  }

  @Override
  public RequestContext getRequestContext() {
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
    return startTimeNano > 0 ? startTimeNano : TimeUnit.MICROSECONDS.toNanos(startTimeMicro);
  }

  @Override
  public long getDurationNano() {
    return durationNano.get();
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

  @Override
  public boolean hasResourceName() {
    return context.hasResourceName();
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

  public Map<String, String> getBaggage() {
    return Collections.unmodifiableMap(context.getBaggageItems());
  }

  @Override
  public String toString() {
    return context.toString() + ", duration_ns=" + durationNano;
  }
}
