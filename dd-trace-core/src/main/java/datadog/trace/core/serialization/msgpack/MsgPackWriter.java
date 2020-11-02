package datadog.trace.core.serialization.msgpack;

import datadog.trace.core.serialization.EncodingCache;

// TODO @FunctionalInterface
public interface MsgPackWriter<T> {
  void write(T value, MsgPacker writable, EncodingCache encodingCache);
}
