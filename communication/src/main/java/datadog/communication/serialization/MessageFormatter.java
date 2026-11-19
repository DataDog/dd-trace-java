package datadog.communication.serialization;

public interface MessageFormatter {
  <T> boolean format(T message, Mapper<T> mapper);

  /**
   * Like {@link #format}, except that a message that doesn't fit in the room left is rewound and
   * {@code false} is returned without sending the buffer, so the caller can flush and decide how to
   * retry it, for example with another mapper.
   */
  <T> boolean formatIfRoom(T message, Mapper<T> mapper);

  void flush();
}
