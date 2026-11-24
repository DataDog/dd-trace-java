package datadog.trace.instrumentation.kafka_clients38;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.datastreams.PathwayContext;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

/**
 * Reads live Kafka header fields through getter handles supplied by the constructor advice. The
 * helper and Kafka's {@code RecordHeader} are loaded together in the application class loader.
 */
public final class RecordHeaderAccess {
  private static final RecordHeaderAccess UNAVAILABLE =
      new RecordHeaderAccess(null, null, null, null);
  private static volatile RecordHeaderAccess access;

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

  private final MethodHandle key;
  private final MethodHandle keyBuffer;
  private final MethodHandle value;
  private final MethodHandle valueBuffer;

  private RecordHeaderAccess(
      MethodHandle key, MethodHandle keyBuffer, MethodHandle value, MethodHandle valueBuffer) {
    this.key = key;
    this.keyBuffer = keyBuffer;
    this.value = value;
    this.valueBuffer = valueBuffer;
  }

  public static boolean needsInitialization() {
    return access == null;
  }

  /** Resolves the field layout once, leaving extraction on Kafka's API if it is unsupported. */
  public static synchronized void initialize(MethodHandles.Lookup lookup) {
    if (!needsInitialization()
        || lookup.lookupClass() != RecordHeader.class
        || (lookup.lookupModes() & MethodHandles.Lookup.PRIVATE) == 0) {
      return;
    }
    try {
      Class<RecordHeader> owner = RecordHeader.class;
      access =
          new RecordHeaderAccess(
              lookup.findGetter(owner, "key", String.class),
              lookup.findGetter(owner, "keyBuffer", ByteBuffer.class),
              lookup.findGetter(owner, "value", byte[].class),
              lookup.findGetter(owner, "valueBuffer", ByteBuffer.class));
    } catch (NoSuchFieldException | IllegalAccessException | SecurityException e) {
      access = UNAVAILABLE;
    }
  }

  static RecordHeaderAccess get() {
    RecordHeaderAccess current = access;
    return current == UNAVAILABLE ? null : current;
  }

  /**
   * The header's key: the decoded key if there is one, a known name's constant if the key bytes
   * spell one, otherwise {@link Header#key()}.
   */
  String key(RecordHeader header) {
    try {
      String key = (String) this.key.invokeExact(header);
      if (key != null) {
        return key;
      }
      ByteBuffer buffer = (ByteBuffer) this.keyBuffer.invokeExact(header);
      if (buffer != null) {
        String known = knownKey(buffer);
        if (known != null) {
          return known;
        }
      }
      return header.key();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new AssertionError("Kafka key getter threw a checked exception", e);
    }
  }

  /**
   * The header's value decoded as UTF-8, or null if the header has no value. Decodes from the
   * copied array if there is one, otherwise from the buffer's backing array; a buffer without an
   * accessible array goes through {@link Header#value()}.
   */
  String utf8Value(RecordHeader header) {
    try {
      byte[] value = (byte[]) this.value.invokeExact(header);
      if (value == null) {
        ByteBuffer buffer = (ByteBuffer) this.valueBuffer.invokeExact(header);
        if (buffer == null) {
          // no value, or value() ran in between and moved the buffer into the array
          value = (byte[]) this.value.invokeExact(header);
        } else if (buffer.hasArray()) {
          return new String(
              buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), UTF_8);
        } else {
          value = header.value();
        }
      }
      return value == null ? null : new String(value, UTF_8);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new AssertionError("Kafka value getter threw a checked exception", e);
    }
  }

  /** The known name whose bytes {@code buffer}'s remaining bytes are, or null. */
  private static String knownKey(ByteBuffer buffer) {
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
