package datadog.trace.common.metrics;

public interface EventListener {
  enum EventType {
    BAD_PAYLOAD,
    DOWNGRADED,
    OK,
    ERROR
  }

  void onEvent(EventType eventType, String message);
}
