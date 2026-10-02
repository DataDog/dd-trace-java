package datadog.trace.config.inversion;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.tabletest.junit.TableTest;

class SupportedConfigurationSourceTest {
  @TableTest({
    "scenario   | otel                            | datadog                ",
    "delay      | OTEL_BLRP_SCHEDULE_DELAY        | DD_LOGS_OTEL_INTERVAL  ",
    "timeout    | OTEL_BLRP_EXPORT_TIMEOUT        | DD_LOGS_OTEL_TIMEOUT   ",
    "queue size | OTEL_BLRP_MAX_QUEUE_SIZE        | DD_LOGS_OTEL_QUEUE_SIZE",
    "batch size | OTEL_BLRP_MAX_EXPORT_BATCH_SIZE | DD_LOGS_OTEL_BATCH_SIZE"
  })
  void supportsBlrpInputsWithoutAliasingDatadogEquivalents(String otel, String datadog) {
    SupportedConfigurationSource source = new SupportedConfigurationSource();

    assertTrue(source.supported(otel));
    assertTrue(source.supported(datadog));
    assertEquals(emptyList(), source.getAliases(otel));
    assertEquals(emptyList(), source.getAliases(datadog));
    assertNull(source.primaryEnvFromAlias(otel));
  }
}
