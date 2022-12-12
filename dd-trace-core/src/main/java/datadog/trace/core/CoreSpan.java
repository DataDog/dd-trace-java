package datadog.trace.core;

import datadog.trace.api.DDTraceId;

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
}
