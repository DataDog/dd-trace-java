package datadog.trace.common.pipeline;

public interface EventListener {
  enum EventType {
    BAD_PAYLOAD,
    DOWNGRADED,
    OK,
    ERROR
  }

  void onEvent(EventType eventType, String message);
}
