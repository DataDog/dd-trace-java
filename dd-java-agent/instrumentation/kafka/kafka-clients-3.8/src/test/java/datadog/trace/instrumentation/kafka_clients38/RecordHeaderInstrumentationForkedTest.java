package datadog.trace.instrumentation.kafka_clients38;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class RecordHeaderInstrumentationForkedTest extends AbstractInstrumentationTest {
  @Test
  void installsGettersAndReadsSlicesWithoutMaterializing() {
    UnderAgent.checkFetched();
  }

  @Test
  void readsKafkaMaterializedValuesAndSubclassOverrides() {
    UnderAgent.checkMaterializedAndOverridden();
  }

  @Test
  void handlesNullValuesAndBuffersWithoutAnAccessibleArray() {
    UnderAgent.checkFallbacks();
  }

  @Test
  void preservesUnrecognizedKeysAndBase64Decoding() {
    UnderAgent.checkKeyAndEncoding();
  }

  // Keep first use of RecordHeader after the forked test installs the agent.
  private static final class UnderAgent {
    static void checkFetched() {
      byte[] keyBytes = "_traceparent_".getBytes(UTF_8);
      ByteBuffer key = ByteBuffer.wrap(keyBytes, 1, keyBytes.length - 2);
      byte[] valueBytes = "_original_".getBytes(UTF_8);
      ByteBuffer value = ByteBuffer.wrap(valueBytes, 1, valueBytes.length - 2).slice();
      RecordHeader header = new RecordHeader(key, value);

      assertNotNull(RecordHeaderAccess.get());
      Map<String, String> seen = extract(header);
      assertSame("traceparent", seen.keySet().iterator().next());
      assertEquals("original", seen.get("traceparent"));
      assertEquals(1, key.position());
      assertEquals(0, value.position());

      // Kafka should materialize the current bytes only when the application reads them.
      keyBytes[1] = 'T';
      valueBytes[1] = 'O';
      assertEquals("Traceparent", header.key());
      assertEquals("Original", new String(header.value(), UTF_8));
    }

    static void checkMaterializedAndOverridden() {
      RecordHeader header =
          new RecordHeader(
              ByteBuffer.wrap("baggage".getBytes(UTF_8)), ByteBuffer.wrap(new byte[] {'a'}));
      header.value()[0] = 'b';
      assertEquals("b", extract(header).get("baggage"));
      assertEquals("transformed", extract(new OverridingHeader()).get("baggage"));
    }

    static void checkFallbacks() {
      ByteBuffer direct = ByteBuffer.allocateDirect(1);
      direct.put((byte) 'a');
      direct.flip();
      RecordHeader directHeader =
          new RecordHeader(ByteBuffer.wrap("baggage".getBytes(UTF_8)), direct);
      RecordHeader readOnlyHeader =
          new RecordHeader(
              ByteBuffer.wrap("baggage".getBytes(UTF_8)),
              ByteBuffer.wrap(new byte[] {'b'}).asReadOnlyBuffer());
      RecordHeader withoutValue =
          new RecordHeader(ByteBuffer.wrap("baggage".getBytes(UTF_8)), null);

      assertEquals("a", extract(directHeader).get("baggage"));
      assertEquals("b", extract(readOnlyHeader).get("baggage"));
      assertFalse(extract(withoutValue).containsKey("baggage"));
    }

    static void checkKeyAndEncoding() {
      RecordHeader header =
          new RecordHeader(
              ByteBuffer.wrap("TraceParent".getBytes(UTF_8)),
              ByteBuffer.wrap("YQ==".getBytes(UTF_8)));
      assertEquals("YQ==", extract(header).get("TraceParent"));
      assertEquals("a", extract(header, true).get("TraceParent"));
    }

    private static Map<String, String> extract(Header header) {
      return extract(header, false);
    }

    private static Map<String, String> extract(Header header, boolean base64) {
      Map<String, String> seen = new LinkedHashMap<>();
      new TextMapExtractAdapter(base64)
          .forEachKey(
              new RecordHeaders(new Header[] {header}),
              (key, value) -> {
                seen.put(key, value);
                return true;
              });
      return seen;
    }

    private static final class OverridingHeader extends RecordHeader {
      OverridingHeader() {
        super(ByteBuffer.wrap("baggage".getBytes(UTF_8)), ByteBuffer.wrap(new byte[] {'a'}));
      }

      @Override
      public byte[] value() {
        return "transformed".getBytes(UTF_8);
      }
    }
  }
}
