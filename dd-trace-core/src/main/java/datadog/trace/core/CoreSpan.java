package datadog.trace.core;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Map;

public interface CoreSpan<T extends CoreSpan<T>> {

  T getLocalRootSpan();

  String getServiceName();

  CharSequence getServiceNameSource();

  CharSequence getOperationName();

  CharSequence getResourceName();

  DDTraceId getTraceId();

  long getSpanId();

  long getParentId();

  long getStartTime();

  long getDurationNano();

  int getError();

  short getHttpStatusCode();

  CharSequence getOrigin();

  T setMeasured(boolean measured);

  T setErrorMessage(final String errorMessage);

  T addThrowable(final Throwable error);

  T setTag(final String tag, final String value);

  T setTag(final String tag, final boolean value);

  T setTag(final String tag, final int value);

  T setTag(final String tag, final long value);

  T setTag(final String tag, final double value);

  T setTag(final String tag, final Number value);

  T setTag(final String tag, final CharSequence value);

  T setTag(final String tag, final Object value);

  T removeTag(final String tag);

  <U> U getTag(CharSequence name, U defaultValue);

  <U> U getTag(CharSequence name);

  <U> U unsafeGetTag(CharSequence name, U defaultValue);

  <U> U unsafeGetTag(CharSequence name);

  boolean hasSamplingPriority();

  boolean isMeasured();

  /**
   * @return whether this span has a different service name from its parent, or is a local root.
   */
  boolean isTopLevel();

  boolean isForceKeep();

  boolean isKind(SpanKindFilter filter);

  /**
   * Returns the {@code span.kind} tag value as a String, or {@code null} if not set. Default
   * implementation reads the tag map; {@link DDSpan} overrides to use a cached ordinal that
   * resolves via a small lookup array, skipping the tag-map lookup on the hot path.
   */
  default String getSpanKindString() {
    Object v = unsafeGetTag(Tags.SPAN_KIND);
    return v == null ? null : v.toString();
  }

  CharSequence getType();

  /**
   * Runs early {@link datadog.trace.core.tagprocessor.TagsPostProcessor} like base service and peer
   * service computation. Such tags are needed before span serialization so they can’t be processed
   * lazily as part of the {@link #processTagsAndBaggage(MetadataConsumer, int)} API.
   */
  void processServiceTags();

  /** The serialization protocol encodes span links natively, so they are not added as a tag. */
  int STRUCTURED_LINKS = 1;

  /** The serialization protocol encodes span events natively, so they are not added as a tag. */
  int STRUCTURED_EVENTS = 1 << 1;

  /**
   * Serializes the span context own propagation tags. This is the default option, for tag-based
   * protocols that serialize each span independently.
   */
  int OWN_PROPAGATION_TAGS = 1 << 2;

  /** Serializes the local root span context propagation tags, as for the first span of a chunk. */
  int ROOT_PROPAGATION_TAGS = 1 << 3;

  /**
   * Processes the span tags and baggage, and hands the resulting {@link Metadata} to the consumer.
   *
   * @param consumer The consumer of the span metadata.
   * @param options A combination of {@link #STRUCTURED_LINKS}, {@link #STRUCTURED_EVENTS}, and
   *     either {@link #OWN_PROPAGATION_TAGS} (the default option) or {@link
   *     #ROOT_PROPAGATION_TAGS}.
   */
  void processTagsAndBaggage(MetadataConsumer consumer, int options);

  T setSamplingPriority(int samplingPriority, int samplingMechanism);

  T setSamplingPriority(
      int samplingPriority, CharSequence rate, double sampleRate, int samplingMechanism);

  default T setSamplingPriority(
      int samplingPriority,
      CharSequence rate,
      double sampleRate,
      int samplingMechanism,
      boolean rateLimiterRejected) {
    return setSamplingPriority(samplingPriority, rate, sampleRate, samplingMechanism);
  }

  T setSpanSamplingPriority(double rate, int limit);

  T setMetric(CharSequence name, int value);

  T setMetric(CharSequence name, long value);

  T setMetric(CharSequence name, float value);

  T setMetric(CharSequence name, double value);

  T setFlag(CharSequence name, boolean value);

  int samplingPriority();

  /**
   * Returns a readonly view of the current meta_struct data stored in the span
   *
   * @return readonly map with all the fields
   */
  Map<String, Object> getMetaStruct();

  /**
   * Adds a new field to the meta_struct stored in the span
   *
   * <p>Existing field value with the same value will be replaced. Setting a field with a {@code
   * null} value will remove the field from the metaStruct.
   *
   * @param field name of the field
   * @param value value of the field
   * @return this
   */
  T setMetaStruct(final String field, final Object value);

  /**
   * Version of a span that can be set by the long running spans feature:
   * <li>eq 0 -> span is not long running.
   * <li>lt 0 -> finished span that had running versions previously written.
   * <li>gt 0 -> long running span and its write version.
   *
   * @return the version.
   */
  int getLongRunningVersion();
}
