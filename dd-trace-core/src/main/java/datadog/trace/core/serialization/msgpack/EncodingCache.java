package datadog.trace.core.serialization.msgpack;

// TODO @FunctionalInterface
public interface EncodingCache {

  byte[] encode(CharSequence s);
}
