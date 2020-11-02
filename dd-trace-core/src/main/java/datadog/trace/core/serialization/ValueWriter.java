package datadog.trace.core.serialization;

// TODO @FunctionalInterface
public interface ValueWriter<T> {
  void write(T value, Writable writable, EncodingCache encodingCache);
}
