package datadog.trace.instrumentation.kafka_clients38;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.BufferedHeaderFields;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/**
 * Under the agent, Kafka's {@code RecordHeader} implements {@link BufferedHeaderFields}. Forked so
 * {@code RecordHeader} is first loaded after the agent is installed: a class loaded before it keeps
 * its structure.
 */
class RecordHeaderInstrumentationForkedTest extends AbstractInstrumentationTest {

  @Test
  void recordHeaderGetsTheAccessor() {
    UnderAgent.checkFetched();
    UnderAgent.checkProduced();
  }

  /**
   * Touches {@code RecordHeader} only from here, so verifying the test class doesn't load it before
   * the agent is installed.
   */
  static final class UnderAgent {
    static void checkFetched() {
      Object header =
          new org.apache.kafka.common.header.internals.RecordHeader(
              ByteBuffer.wrap("traceparent".getBytes(UTF_8)),
              ByteBuffer.wrap("00-abc-def-01".getBytes(UTF_8)));
      assertTrue(header instanceof BufferedHeaderFields, "RecordHeader wasn't instrumented");
      BufferedHeaderFields fields = (BufferedHeaderFields) header;
      assertNull(fields.$$DD$$__key());
      assertEquals(ByteBuffer.wrap("traceparent".getBytes(UTF_8)), fields.$$DD$$__keyBuffer());
      assertNull(fields.$$DD$$__value());
      assertEquals(ByteBuffer.wrap("00-abc-def-01".getBytes(UTF_8)), fields.$$DD$$__valueBuffer());

      org.apache.kafka.common.header.Header asHeader =
          (org.apache.kafka.common.header.Header) header;
      assertSame("traceparent", RecordHeaderAccess.key(asHeader, fields));
      assertEquals("00-abc-def-01", RecordHeaderAccess.utf8Value(asHeader, fields));
      // still unmaterialized for the application
      assertNull(fields.$$DD$$__key());
      assertNull(fields.$$DD$$__value());
    }

    static void checkProduced() {
      Object header =
          new org.apache.kafka.common.header.internals.RecordHeader(
              "baggage", "k=v".getBytes(UTF_8));
      BufferedHeaderFields fields = (BufferedHeaderFields) header;
      assertEquals("baggage", fields.$$DD$$__key());
      assertNull(fields.$$DD$$__keyBuffer());
      assertArrayEquals("k=v".getBytes(UTF_8), fields.$$DD$$__value());
      assertNull(fields.$$DD$$__valueBuffer());
    }
  }
}
