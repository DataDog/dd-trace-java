package datadog.trace.bootstrap.instrumentation.api;

import java.nio.ByteBuffer;

/**
 * Read access to a lazily decoded message header's fields, installed on the header type by the
 * agent (Kafka's {@code RecordHeader}), so extraction can read a fetched header from its buffers
 * without the copies the header's own accessors make.
 *
 * <p>Each getter returns the field as it is; none decodes, copies or changes anything. A buffer
 * returned here is a slice of a whole fetch buffer and must not be kept past the read.
 */
public interface BufferedHeaderFields {
  /** The decoded key, or null if not decoded yet. */
  String $$DD$$__key();

  /** The undecoded key, or null once decoded. */
  ByteBuffer $$DD$$__keyBuffer();

  /** The value bytes, or null if not copied yet (or the header has no value). */
  byte[] $$DD$$__value();

  /** The uncopied value, or null once copied (or the header has no value). */
  ByteBuffer $$DD$$__valueBuffer();
}
