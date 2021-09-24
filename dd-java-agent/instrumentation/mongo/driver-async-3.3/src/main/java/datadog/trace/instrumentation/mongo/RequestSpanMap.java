package datadog.trace.instrumentation.mongo;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RequestSpanMap {
  private final Map<Integer, AgentSpan> spanMap = new ConcurrentHashMap<>();

  public AgentSpan get(Integer id) {
    return spanMap.get(id);
  }

  public AgentSpan put(Integer integer, AgentSpan span) {
    return spanMap.put(integer, span);
  }

  public AgentSpan remove(Integer id) {
    return spanMap.remove(id);
  }
}
