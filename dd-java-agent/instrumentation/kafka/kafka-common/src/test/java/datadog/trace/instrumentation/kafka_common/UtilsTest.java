package datadog.trace.instrumentation.kafka_common;

import static datadog.trace.instrumentation.kafka_common.Utils.hasHeaders;
import static datadog.trace.instrumentation.kafka_common.Utils.readProducedTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UtilsTest {
  @ParameterizedTest
  @ValueSource(longs = {0L, 1L, 1_793_652_306_477L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0x80L})
  void readsTheTimeAProducerWrote(long time) {
    byte[] bytes = ByteBuffer.allocate(Long.BYTES).putLong(time).array();

    assertEquals(time, readProducedTime(bytes));
  }

  @Test
  void rejectsAValueThatIsNotEightBytes() {
    assertThrows(IllegalArgumentException.class, () -> readProducedTime(new byte[7]));
    assertThrows(IllegalArgumentException.class, () -> readProducedTime(new byte[9]));
  }

  @Test
  void recordWithoutHeadersHasNone() {
    assertFalse(hasHeaders(new RecordHeaders()));
  }

  @Test
  void recordWithAHeaderHasHeaders() {
    RecordHeaders headers = new RecordHeaders();
    headers.add("x-datadog-trace-id", new byte[] {'1'});

    assertTrue(hasHeaders(headers));
  }

  @Test
  void readOnlyHeadersCanBeChecked() {
    RecordHeaders headers = new RecordHeaders();
    headers.setReadOnly();

    assertFalse(hasHeaders(headers));
  }
}
