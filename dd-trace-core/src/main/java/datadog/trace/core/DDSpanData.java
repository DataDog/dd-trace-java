package datadog.trace.core;

import datadog.trace.api.DDId;
import java.util.Map;

public interface DDSpanData {

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

  Map<String, String> getBaggage();

  Map<String, Object> getTags();

  CharSequence getType();

  void processTagsAndBaggage(TagsAndBaggageConsumer consumer);
}
