package datadog.trace.test.agent.decoder;

import java.util.Map;

public interface DecodedSpan {
  String getService();

  String getName();

  String getResource();

  long getTraceId();

  long getSpanId();

  long getParentId();

  long getStart();

  long getDuration();

  int getError();

  Map<String, String> getMeta();

  Map<String, Object> getMetaStruct();

  Map<String, Number> getMetrics();

  String getType();
}
