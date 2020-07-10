package datadog.trace.core.serialization.msgpack;

// TODO @FunctionalInterface
public interface Writer<T> {
  void write(T value, Packer writable, EncodingCache encodingCache);
}
