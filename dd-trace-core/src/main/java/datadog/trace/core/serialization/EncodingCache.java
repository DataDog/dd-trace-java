package datadog.trace.core.serialization;

// TODO @FunctionalInterface
public interface EncodingCache {

  byte[] encode(CharSequence s);
}
