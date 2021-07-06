package datadog.communication.serialization;

public interface MessageFormatter {
  <T> boolean format(T message, Mapper<T> mapper);

  void flush();
}
