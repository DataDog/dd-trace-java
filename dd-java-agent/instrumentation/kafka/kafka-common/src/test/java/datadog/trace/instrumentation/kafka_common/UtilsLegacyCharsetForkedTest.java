package datadog.trace.instrumentation.kafka_common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import org.junit.jupiter.api.BeforeAll;

class UtilsLegacyCharsetForkedTest extends UtilsTest {
  @BeforeAll
  static void requiresLegacyDefaultCharset() {
    assertEquals(Charset.forName("windows-1252"), Charset.defaultCharset());
  }
}
