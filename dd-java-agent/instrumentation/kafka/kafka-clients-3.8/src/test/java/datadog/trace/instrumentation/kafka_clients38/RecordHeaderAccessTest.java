package datadog.trace.instrumentation.kafka_clients38;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.BufferedHeaderFields;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.type.TypeDescription.ForLoadedType;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

/**
 * {@link RecordHeaderAccess} over real {@link RecordHeader}s. Without the agent, {@link
 * FieldsHeader} stands in for the accessor {@link RecordHeaderInstrumentation} installs, reading
 * the same fields reflectively; {@link RecordHeaderInstrumentationForkedTest} checks the installed
 * one.
 */
class RecordHeaderAccessTest {

  /** A header as the consumer builds it: key and value are slices of one larger fetch buffer. */
  static FieldsHeader fetched(String key, String value) {
    byte[] keyBytes = key.getBytes(UTF_8);
    byte[] valueBytes = value == null ? null : value.getBytes(UTF_8);
    int valueLength = valueBytes == null ? 0 : valueBytes.length;
    byte[] batch = new byte[7 + keyBytes.length + 5 + valueLength + 3];
    System.arraycopy(keyBytes, 0, batch, 7, keyBytes.length);
    ByteBuffer keyBuffer = ByteBuffer.wrap(batch, 7, keyBytes.length).slice();
    ByteBuffer valueBuffer = null;
    if (valueBytes != null) {
      int valueStart = 7 + keyBytes.length + 5;
      System.arraycopy(valueBytes, 0, batch, valueStart, valueLength);
      // a positioned view rather than a slice, so both position and arrayOffset are exercised
      valueBuffer = ByteBuffer.wrap(batch);
      valueBuffer.position(valueStart).limit(valueStart + valueLength);
    }
    return new FieldsHeader(keyBuffer, valueBuffer);
  }

  private static String key(FieldsHeader header) {
    return RecordHeaderAccess.key(header, header);
  }

  private static String value(FieldsHeader header) {
    return RecordHeaderAccess.utf8Value(header, header);
  }

  @Test
  void knownKeyResolvesToItsConstant() {
    assertSame("traceparent", key(fetched("traceparent", "v")));
    assertSame("x-datadog-tags", key(fetched("x-datadog-tags", "v")));
  }

  @Test
  void unknownKeyDecodesAsKafkaWould() {
    assertEquals("content-type", key(fetched("content-type", "v")));
    // a prefix or extension of a known name isn't that name
    assertEquals("traceparentx", key(fetched("traceparentx", "v")));
    assertEquals("tracepar", key(fetched("tracepar", "v")));
  }

  @Test
  void valueDecodesFromTheFetchBuffer() {
    String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    assertEquals(traceparent, value(fetched("traceparent", traceparent)));
    assertEquals("é✓", value(fetched("custom", "é✓")));
    assertEquals("", value(fetched("custom", "")));
  }

  @Test
  void missingValueIsNull() {
    assertNull(value(fetched("traceparent", null)));
    assertNull(value(new FieldsHeader("traceparent", null)));
  }

  @Test
  void readingLeavesKafkaStateAlone() {
    FieldsHeader header = fetched("tracestate", "dd=s:1");
    key(header);
    value(header);
    value(header);
    // Kafka's own accessors still see untouched buffers
    assertEquals("tracestate", header.key());
    assertArrayEquals("dd=s:1".getBytes(UTF_8), header.value());
  }

  @Test
  void alreadyMaterializedHeadersReadTheirFields() {
    FieldsHeader header = fetched("content-type", "text/plain");
    String decoded = header.key();
    header.value();
    assertSame(decoded, key(header));
    assertEquals("text/plain", value(header));

    FieldsHeader produced = new FieldsHeader("baggage", "k=v".getBytes(UTF_8));
    assertEquals("baggage", key(produced));
    assertEquals("k=v", value(produced));
  }

  @Test
  void directBufferValueFallsBackToKafka() {
    byte[] bytes = "dd=s:2".getBytes(UTF_8);
    ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
    direct.put(bytes).flip();
    FieldsHeader header = new FieldsHeader(ByteBuffer.wrap("tracestate".getBytes(UTF_8)), direct);
    assertEquals("dd=s:2", value(header));
  }

  @Test
  void adapterVisitsTheSameKeysAndValues() {
    RecordHeaders headers = new RecordHeaders();
    headers.add(fetched("x-datadog-trace-id", "123"));
    headers.add(fetched("content-type", "application/json"));
    headers.add(fetched("traceparent", null));
    headers.add(fetched("tracestate", "dd=s:1;t.dm:-1,other=x"));
    // neither has the accessor, so both go through key() and value()
    headers.add(new RecordHeader("x-datadog-parent-id", "456".getBytes(UTF_8)));
    headers.add(new OtherHeader("x-datadog-origin", "synthetics"));

    List<String> visited = visit(new TextMapExtractAdapter(false), headers);
    List<String> expected = new ArrayList<>();
    for (Header header : headers) {
      if (header.value() != null) {
        expected.add(header.key() + "=" + new String(header.value(), UTF_8));
      }
    }
    assertEquals(expected, visited);
    assertEquals(5, visited.size());
  }

  @Test
  void adapterStopsWhenTheClassifierSaysSo() {
    RecordHeaders headers = new RecordHeaders();
    headers.add(fetched("x-datadog-trace-id", "123"));
    headers.add(fetched("x-datadog-parent-id", "456"));
    List<String> seen = new ArrayList<>();
    new TextMapExtractAdapter(false)
        .forEachKey(
            headers,
            (key, value) -> {
              seen.add(key);
              return false;
            });
    assertEquals(1, seen.size());
  }

  @Test
  void fieldsMatchWhatTheInstrumentationExpects() {
    assertTrue(
        RecordHeaderInstrumentation.hasExpectedFields(
            ForLoadedType.of(RecordHeader.class).getDeclaredFields()),
        "RecordHeader's layout changed; the accessor won't be installed");
  }

  private static List<String> visit(TextMapExtractAdapter adapter, RecordHeaders headers) {
    List<String> seen = new ArrayList<>();
    adapter.forEachKey(
        headers,
        (key, value) -> {
          seen.add(key + "=" + value);
          return true;
        });
    return seen;
  }

  /**
   * A {@link RecordHeader} with the accessor the agent installs, its getters reading the same
   * fields reflectively.
   */
  static final class FieldsHeader extends RecordHeader implements BufferedHeaderFields {
    private static final Field KEY = field("key");
    private static final Field KEY_BUFFER = field("keyBuffer");
    private static final Field VALUE = field("value");
    private static final Field VALUE_BUFFER = field("valueBuffer");

    FieldsHeader(ByteBuffer keyBuffer, ByteBuffer valueBuffer) {
      super(keyBuffer, valueBuffer);
    }

    FieldsHeader(String key, byte[] value) {
      super(key, value);
    }

    private static Field field(String name) {
      try {
        Field field = RecordHeader.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException e) {
        throw new IllegalStateException(e);
      }
    }

    private Object get(Field field) {
      try {
        return field.get(this);
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public String $$DD$$__key() {
      return (String) get(KEY);
    }

    @Override
    public ByteBuffer $$DD$$__keyBuffer() {
      return (ByteBuffer) get(KEY_BUFFER);
    }

    @Override
    public byte[] $$DD$$__value() {
      return (byte[]) get(VALUE);
    }

    @Override
    public ByteBuffer $$DD$$__valueBuffer() {
      return (ByteBuffer) get(VALUE_BUFFER);
    }
  }

  /** A header type other than Kafka's own, which takes the key()/value() path. */
  private static final class OtherHeader implements Header {
    private final String key;
    private final byte[] value;

    OtherHeader(String key, String value) {
      this.key = key;
      this.value = value.getBytes(UTF_8);
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public byte[] value() {
      return value;
    }
  }
}
