package datadog.trace.instrumentation.kafka_clients38;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class RecordHeaderLookupFallbackForkedTest {
  @Test
  void usesKafkaAccessorsWhenNoAuthorizedLookupWasInstalled() {
    RecordHeaderAccess.initialize(MethodHandles.lookup());
    RecordHeaderAccess.initialize(MethodHandles.publicLookup().in(RecordHeader.class));
    assertTrue(RecordHeaderAccess.needsInitialization());

    byte[] value = {'a'};
    RecordHeader header =
        new RecordHeader(ByteBuffer.wrap("baggage".getBytes(UTF_8)), ByteBuffer.wrap(value));

    Map<String, String> seen = new HashMap<>();
    new TextMapExtractAdapter(false)
        .forEachKey(
            new RecordHeaders(new Header[] {header}),
            (key, decoded) -> {
              seen.put(key, decoded);
              return true;
            });
    assertEquals(Collections.singletonMap("baggage", "a"), seen);

    value[0] = 'b';
    assertEquals("a", new String(header.value(), UTF_8));
  }
}
