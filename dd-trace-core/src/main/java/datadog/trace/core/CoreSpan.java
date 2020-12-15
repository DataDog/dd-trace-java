package datadog.trace.core;

import datadog.trace.api.DDId;
import java.util.Map;

public interface CoreSpan<T extends CoreSpan<T>> {

  T getLocalRootSpan();

  String getServiceName();

  CharSequence getOperationName();

  CharSequence getResourceName();

  DDId getTraceId();

  DDId getSpanId();

  DDId getParentId();

  long getStartTime();

  long getDurationNano();

  int getError();

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

  Map<CharSequence, Number> getUnsafeMetrics();

  CharSequence getType();

  void processTagsAndBaggage(MetadataConsumer consumer);

  T setSamplingPriority(int samplingPriority);

  T setSamplingPriority(int samplingPriority, CharSequence rate, double sampleRate);

  T setMetric(CharSequence name, int value);

  T setMetric(CharSequence name, long value);

  T setMetric(CharSequence name, float value);

  T setMetric(CharSequence name, double value);

  T setFlag(CharSequence name, boolean value);

  int samplingPriority();
}
