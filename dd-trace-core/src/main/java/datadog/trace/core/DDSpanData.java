package datadog.trace.core;

import datadog.trace.api.DDId;
import java.util.Map;

public interface DDSpanData {

  String getServiceName();

  String getOperationName();

  CharSequence getResourceName();

  DDId getTraceId();

  DDId getSpanId();

  DDId getParentId();

  long getStartTime();

  long getDurationNano();

  int getError();

  Map<String, Number> getMetrics();

  Map<String, String> getBaggage();

  Map<String, Object> getTags();

  String getType();

  void processTagsAndBaggage(TagsAndBaggageConsumer consumer);
}
