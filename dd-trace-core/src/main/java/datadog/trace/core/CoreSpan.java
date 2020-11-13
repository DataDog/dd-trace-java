package datadog.trace.core;

import datadog.trace.api.DDId;
import java.util.Map;

public interface CoreSpan<T extends CoreSpan<T>> {

  String getServiceName();

  CharSequence getOperationName();

  CharSequence getResourceName();

  DDId getTraceId();

  DDId getSpanId();

  DDId getParentId();

  long getStartTime();

  long getDurationNano();

  int getError();

  boolean isMeasured();

  Map<CharSequence, Number> getMetrics();

  CharSequence getType();

  void processTagsAndBaggage(TagsAndBaggageConsumer consumer);

  T setSamplingPriority(int samplingPriority);

  T setSamplingPriority(int samplingPriority, double sampleRate);

  T setMetric(CharSequence name, int value);

  T setMetric(CharSequence name, long value);

  T setMetric(CharSequence name, float value);

  T setMetric(CharSequence name, double value);

  T setFlag(CharSequence name, boolean value);
}
