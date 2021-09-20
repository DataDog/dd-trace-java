package datadog.trace.instrumentation.mongo;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionSpanMap {
  private static final Map<Object, Map<Integer, AgentSpan>> map = new HashMap<>();

  public static AgentSpan removeSpan(Object connection, int requestId) {
    return getRequestMap(connection).remove(requestId);
  }

  public static void addSpan(Object connection, int requestId, AgentSpan span) {
    getOrAddRequestMap(connection).put(requestId, span);
  }

  private static Map<Integer, AgentSpan> getRequestMap(Object connection) {
    synchronized (map) {
      Map<Integer, AgentSpan> requestMap = map.get(connection);
      return requestMap != null ? requestMap : Collections.<Integer, AgentSpan>emptyMap();
    }
  }

  private static Map<Integer, AgentSpan> getOrAddRequestMap(Object connection) {
    Map<Integer, AgentSpan> requestMap;
    synchronized (map) {
      requestMap = map.get(connection);
      if (requestMap == null) {
        requestMap = new ConcurrentHashMap<>();
        map.put(connection, requestMap);
      }
    }
    return requestMap;
  }
}
