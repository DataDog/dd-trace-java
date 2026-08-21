package datadog.communication.serialization;

@FunctionalInterface
public interface Mapper<T> {
  void map(T data, Writable packer);

  default void map(T data, Writable packer, boolean retry) {
    map(data, packer);
  }

  default void reset() {}
}
