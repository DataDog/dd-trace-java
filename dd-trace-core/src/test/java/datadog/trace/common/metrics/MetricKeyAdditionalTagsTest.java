package datadog.trace.common.metrics;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class MetricKeyAdditionalTagsTest {

  @Test
  void nullAndEmptyArrayAreEquivalent() {
    MetricKey a = key(null);
    MetricKey b = key(new String[0]);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertArrayEquals(new String[0], a.getAdditionalTagValues());
  }

  @Test
  void sameValuesProduceEqualKeys() {
    MetricKey a = key(new String[] {"us-east-1", "acme"});
    MetricKey b = key(new String[] {"us-east-1", "acme"});
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void differentValuesProduceDifferentKeys() {
    MetricKey a = key(new String[] {"us-east-1", null});
    MetricKey b = key(new String[] {"eu-west-1", null});
    assertNotEquals(a, b);
  }

  @Test
  void differentTagPresenceProducesDifferentKeys() {
    MetricKey a = key(new String[] {"us-east-1", null});
    MetricKey b = key(new String[] {"us-east-1", "acme"});
    assertNotEquals(a, b);
  }

  @Test
  void keyWithAdditionalTagsDiffersFromKeyWithout() {
    MetricKey a = key(new String[0]);
    MetricKey b = key(new String[] {"us-east-1"});
    assertNotEquals(a, b);
  }

  private static MetricKey key(String[] additionalTagValues) {
    return new MetricKey(
        "resource",
        "service",
        "operation",
        null,
        "web",
        200,
        false,
        false,
        "server",
        emptyList(),
        null,
        null,
        null,
        additionalTagValues);
  }
}
