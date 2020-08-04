package datadog.trace.core.serialization.msgpack;

public interface MessageFormatter {
  <T> boolean format(T message, Mapper<T> mapper);

  void flush();
}
