package datadog.trace.instrumentation.mongo4;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RequestSpanMap {
  public static final class RequestSpan {
    public final int requestId;
    public final AgentSpan span;
    public volatile boolean isAsync = false;

    public RequestSpan(int requestId, AgentSpan span) {
      this.requestId = requestId;
      this.span = span;
    }
  }

  private static final Map<Integer, RequestSpan> SPAN_MAP = new ConcurrentHashMap<>();

  public static void addRequestSpan(int requestId, AgentSpan span) {
    SPAN_MAP.put(requestId, new RequestSpan(requestId, span));
  }

  public static RequestSpan getRequestSpan(int requestId) {
    return SPAN_MAP.get(requestId);
  }

  public static RequestSpan removeRequestSpan(int requestId) {
    return SPAN_MAP.remove(requestId);
  }
}
