package datadog.trace.api.interceptor;

import java.util.Map;

public interface MutableSpan {

  /**
   * @return Start time with nanosecond scale, but millisecond resolution.
   */
  long getStartTime();

  /**
   * @return Duration with nanosecond scale.
   */
  long getDurationNano();

  CharSequence getOperationName();

  MutableSpan setOperationName(final CharSequence serviceName);

  String getServiceName();

  MutableSpan setServiceName(final String serviceName);

  CharSequence getResourceName();

  MutableSpan setResourceName(final CharSequence resourceName);

  String getSpanType();

  MutableSpan setSpanType(final CharSequence type);

  Map<String, Object> getTags();

  default Object getTag(String key) {
    Map<String, Object> tags = getTags();
    return tags == null ? null : tags.get(key);
  }

  MutableSpan setTag(final String tag, final String value);

  MutableSpan setTag(final String tag, final boolean value);

  MutableSpan setTag(final String tag, final Number value);

  MutableSpan setMetric(final CharSequence metric, final int value);

  MutableSpan setMetric(final CharSequence metric, final long value);

  MutableSpan setMetric(final CharSequence metric, final float value);

  MutableSpan setMetric(final CharSequence metric, final double value);

  boolean isError();

  MutableSpan setError(boolean value);
}
