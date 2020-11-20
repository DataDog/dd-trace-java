package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDId;
import java.util.Map;

/** This interface exposes the set of data necessary for serializing the span. */
public interface AgentSpanData {
  DDId getTraceId();

  DDId getSpanId();

  DDId getParentId();

  /** @return Start time with nanosecond scale, but millisecond resolution. */
  long getStartTime();

  /** @return Duration with nanosecond scale. */
  long getDurationNano();

  CharSequence getOperationName();

  String getServiceName();

  CharSequence getResourceName();

  CharSequence getType();

  boolean isMeasured();

  <U> U getTag(CharSequence name, U defaultValue);

  <U> U getTag(CharSequence name);

  Map<CharSequence, Number> getMetrics();

  int getError();

  void processTagsAndBaggage(TagsAndBaggageConsumer consumer);
}
