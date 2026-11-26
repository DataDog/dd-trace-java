package datadog.trace.api.datastreams;

/**
 * Implemented by carrier setters that store header values as bytes, so the Data Streams propagator
 * can hand over the encoded pathway context without building a {@code String} that the setter would
 * convert back.
 */
public interface BinaryCarrierSetter<C> {
  /** {@code value} is ASCII; it must be stored as is. */
  void setBytes(C carrier, String key, byte[] value);
}
