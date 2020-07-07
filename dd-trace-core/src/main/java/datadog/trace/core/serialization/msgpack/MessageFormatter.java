package datadog.trace.core.serialization.msgpack;

public interface MessageFormatter {
  <T> void format(T message, Mapper<T> mapper);

  void flush();
}
