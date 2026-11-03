package datadog.trace.instrumentation.kafka_common;

import static datadog.trace.instrumentation.kafka_common.Utils.hasHeaders;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class UtilsTest {

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
