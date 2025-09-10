package datadog.trace.core;

import datadog.trace.api.DDTraceId;
import java.util.Map;

public interface CoreSpan<T extends CoreSpan<T>> {

  T getLocalRootSpan();

  String getServiceName();

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

  boolean hasSamplingPriority();

  boolean isMeasured();

  /** @return whether this span has a different service name from its parent, or is a local root. */
  boolean isTopLevel();

  boolean isForceKeep();

  CharSequence getType();

  /**
   * Runs early {@link datadog.trace.core.tagprocessor.TagsPostProcessor} like base service and peer
   * service computation. Such tags are needed before span serialization so they can’t be processed
   * lazily as part of the {@link #processTagsAndBaggage(MetadataConsumer)} API.
   */
  void processServiceTags();

  void processTagsAndBaggage(MetadataConsumer consumer);

  T setSamplingPriority(int samplingPriority, int samplingMechanism);

  T setSamplingPriority(
      int samplingPriority, CharSequence rate, double sampleRate, int samplingMechanism);

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
