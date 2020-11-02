package datadog.trace.core.serialization;

public interface WritableFormatter extends Writable, MessageFormatter {
  int messageCount();

  void reset();
}
