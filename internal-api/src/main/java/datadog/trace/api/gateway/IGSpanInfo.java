package datadog.trace.api.gateway;

import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;

public interface IGSpanInfo {
  DDId getTraceId();

  DDId getSpanId();

  Map<String, Object> getTags();

  AgentSpan setTag(String key, boolean value);

  void markForBlocking();

  boolean isToBeBlocked();
}
