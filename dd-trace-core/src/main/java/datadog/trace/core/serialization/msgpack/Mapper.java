package datadog.trace.core.serialization.msgpack;

// TODO @FunctionalInterface
public interface Mapper<T> {
  void map(T data, Writable packer);
}
