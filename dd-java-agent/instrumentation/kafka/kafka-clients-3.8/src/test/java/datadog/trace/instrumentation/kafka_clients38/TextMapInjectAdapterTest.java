package datadog.trace.instrumentation.kafka_clients38;

import static datadog.trace.instrumentation.kafka_clients38.TextMapInjectAdapter.SETTER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class TextMapInjectAdapterTest {

  @Test
  void addsAHeaderThatIsNotThereYet() {
    Headers headers = new RecordHeaders();

    SETTER.set(headers, "traceparent", "00-abc-def-01");

    assertValues(headers, "traceparent", "00-abc-def-01");
  }

  @Test
  void replacesAHeaderAlreadyThere() {
    Headers headers = new RecordHeaders();
    headers.add("x-datadog-trace-id", "1".getBytes(UTF_8));
    headers.add("x-datadog-trace-id", "2".getBytes(UTF_8));

    SETTER.set(headers, "x-datadog-trace-id", "3");

    assertValues(headers, "x-datadog-trace-id", "3");
  }

  /** The Datadog and W3C codecs both write ot-baggage- keys, and the record must carry one. */
  @Test
  void keepsOneHeaderWhenTheSameKeyIsSetTwice() {
    Headers headers = new RecordHeaders();

    SETTER.set(headers, "ot-baggage-t0", "100");
    SETTER.set(headers, "ot-baggage-t0", "200");

    assertValues(headers, "ot-baggage-t0", "200");
  }

  /** The producer advice relies on this to retry with a writable copy of the record. */
  @Test
  void throwsOnReadOnlyHeaders() {
    RecordHeaders empty = new RecordHeaders();
    empty.setReadOnly();
    RecordHeaders withKey = new RecordHeaders();
    withKey.add("traceparent", "old".getBytes(UTF_8));
    withKey.setReadOnly();

    assertThrows(IllegalStateException.class, () -> SETTER.set(empty, "traceparent", "new"));
    assertThrows(IllegalStateException.class, () -> SETTER.set(withKey, "traceparent", "new"));
  }

  private static void assertValues(Headers headers, String key, String... expected) {
    List<byte[]> values = new ArrayList<>();
    for (Header header : headers.headers(key)) {
      values.add(header.value());
    }
    assertEquals(expected.length, values.size());
    for (int i = 0; i < expected.length; i++) {
      assertArrayEquals(expected[i].getBytes(UTF_8), values.get(i));
    }
  }
}
