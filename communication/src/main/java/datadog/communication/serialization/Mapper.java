package datadog.communication.serialization;

@FunctionalInterface
public interface Mapper<T> {
  void map(T data, Writable packer);

  default void reset() {}
}
