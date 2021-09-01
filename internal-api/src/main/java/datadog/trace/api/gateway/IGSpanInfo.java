package datadog.trace.api.gateway;

import datadog.trace.api.DDId;
import java.util.Map;

public interface IGSpanInfo {
  DDId getTraceId();

  DDId getSpanId();

  Map<String, Object> getTags();
}
