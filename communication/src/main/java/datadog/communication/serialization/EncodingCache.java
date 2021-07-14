package datadog.communication.serialization;

// TODO @FunctionalInterface
public interface EncodingCache {

  byte[] encode(CharSequence s);
}
