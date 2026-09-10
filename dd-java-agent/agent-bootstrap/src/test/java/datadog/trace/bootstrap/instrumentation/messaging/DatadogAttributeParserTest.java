package datadog.trace.bootstrap.instrumentation.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the one behaviour this branch adds to the {@code _datadog} message attribute parser shared
 * by the AWS messaging instrumentations (SQS, SNS, EventBridge, Step Functions): {@code
 * x-datadog-tags} is forwarded to the extractor.
 */
class DatadogAttributeParserTest {

  @Test
  void forwardsPropagationTags() {
    Map<String, String> collected = new LinkedHashMap<>();
    DatadogAttributeParser.forEachProperty(
        (key, value) -> {
          collected.put(key, value);
          return true;
        },
        "{\"x-datadog-trace-id\":\"1234567890\","
            + "\"x-datadog-parent-id\":\"9876543210\","
            + "\"x-datadog-sampling-priority\":\"1\","
            + "\"x-datadog-tags\":\"_dd.p.dm=-1,_dd.p.tid=6aa01c5400000000\"}");

    assertEquals("_dd.p.dm=-1,_dd.p.tid=6aa01c5400000000", collected.get("x-datadog-tags"));
  }
}
