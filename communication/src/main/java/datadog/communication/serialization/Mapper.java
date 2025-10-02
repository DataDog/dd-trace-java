package datadog.communication.serialization;

// TODO @FunctionalInterface
public interface Mapper<T> {
  void map(T data, Writable packer);

  default void reset() {}
}
