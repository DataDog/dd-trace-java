package datadog.trace.instrumentation.kafka_clients38;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.BufferedHeaderFields;
import java.nio.ByteBuffer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

/**
 * Reads a fetched Kafka header's key and value straight from the record batch, skipping the copies
 * the header's lazy accessors make.
 *
 * <p>A fetched {@code RecordHeader} holds slices of the fetch buffer. Its {@code key()} decodes the
 * key slice into a new String and {@code value()} copies the value slice into a new array, for
 * every header, whether or not anything wants it. Here a key that is a known propagation header
 * name resolves to that name's constant, and a value decodes once, from the buffer's backing array.
 *
 * <p>The fields are read through {@link BufferedHeaderFields}, which {@link
 * RecordHeaderInstrumentation} installs on {@code RecordHeader}. Read-only: the header's fields and
 * buffer positions are left as they are, so the application's own {@code key()} and {@code value()}
 * calls behave as before. No buffer reference outlives a call: each would keep the whole fetch
 * buffer alive.
 */
final class RecordHeaderAccess {
  /**
   * Header names the tracer reads on extraction, as written by tracers on inject. Only a fast path:
   * a key not listed here is still read, through {@link Header#key()}, so a name missing from this
   * list costs a decode, never correctness.
   */
  private static final String[] KNOWN_KEYS = {
    "x-datadog-trace-id",
    "x-datadog-parent-id",
    "x-datadog-sampling-priority",
    "x-datadog-origin",
    "x-datadog-tags",
    "traceparent",
    "tracestate",
    "baggage",
    PathwayContext.PROPAGATION_KEY_BASE64,
    KafkaDecorator.KAFKA_PRODUCED_KEY,
  };

  private static final ClassValue<Boolean> OVERRIDES_ACCESSORS = new OverridesAccessors();

  /**
   * Whether a {@code RecordHeader} subclass overrides {@code key()} or {@code value()}. A named
   * class rather than an anonymous one, so instrumentations can declare it as a helper.
   */
  static final class OverridesAccessors extends ClassValue<Boolean> {
    @Override
    protected Boolean computeValue(Class<?> type) {
      try {
        return type.getMethod("key").getDeclaringClass() != RecordHeader.class
            || type.getMethod("value").getDeclaringClass() != RecordHeader.class;
      } catch (NoSuchMethodException e) {
        return true;
      }
    }
  }

  private RecordHeaderAccess() {}

  /**
   * The header's buffered fields if it can be read through them: a {@code RecordHeader} the agent
   * gave {@link BufferedHeaderFields}, or a subclass that keeps Kafka's own {@code key()} and
   * {@code value()} (an override, say one that decrypts, must be read through itself); otherwise
   * null. Exactly {@code RecordHeader}, the usual case, is checked first, which keeps the interface
   * test constant once compiled rather than a lookup per header.
   */
  static BufferedHeaderFields bufferedFields(Header header) {
    Class<?> type = header.getClass();
    if (type == RecordHeader.class) {
      return header instanceof BufferedHeaderFields ? (BufferedHeaderFields) header : null;
    }
    return header instanceof BufferedHeaderFields && !OVERRIDES_ACCESSORS.get(type)
        ? (BufferedHeaderFields) header
        : null;
  }

  /**
   * The header's key: the decoded key if there is one, a known name's constant if the key bytes
   * spell one, otherwise {@link Header#key()}.
   */
  static String key(Header header, BufferedHeaderFields fields) {
    String key = fields.$$DD$$__key();
    if (key != null) {
      return key;
    }
    ByteBuffer buffer = fields.$$DD$$__keyBuffer();
    if (buffer != null) {
      String known = knownKey(buffer);
      if (known != null) {
        return known;
      }
    }
    return header.key();
  }

  /**
   * The header's value decoded as UTF-8, or null if the header has no value. Decodes from the
   * copied array if there is one, otherwise from the buffer's backing array; a direct buffer goes
   * through {@link Header#value()}.
   */
  static String utf8Value(Header header, BufferedHeaderFields fields) {
    byte[] value = fields.$$DD$$__value();
    if (value == null) {
      ByteBuffer buffer = fields.$$DD$$__valueBuffer();
      if (buffer == null) {
        // no value, or value() ran in between and moved the buffer into the array
        value = fields.$$DD$$__value();
      } else if (buffer.hasArray()) {
        return new String(
            buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), UTF_8);
      } else {
        value = header.value();
      }
    }
    return value == null ? null : new String(value, UTF_8);
  }

  /** The known name whose bytes {@code buffer}'s remaining bytes are, or null. */
  static String knownKey(ByteBuffer buffer) {
    int length = buffer.remaining();
    int start = buffer.position();
    for (String known : KNOWN_KEYS) {
      if (known.length() == length && matches(buffer, start, known)) {
        return known;
      }
    }
    return null;
  }

  private static boolean matches(ByteBuffer buffer, int start, String known) {
    for (int i = 0, n = known.length(); i < n; i++) {
      if (buffer.get(start + i) != (byte) known.charAt(i)) {
        return false;
      }
    }
    return true;
  }
}
