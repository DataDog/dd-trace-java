package datadog.trace.core.serialization;

// TODO @FunctionalInterface
public interface Mapper<T> {
  void map(T data, Writable packer);
}
