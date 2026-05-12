package datadog.trace.common.metrics;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricKeyAdditionalTagsTest {

  @Test
  void emptyAndNullAdditionalTagsAreEquivalent() {
    MetricKey a = key(null);
    MetricKey b = key(emptyList());
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertEquals(emptyList(), a.getAdditionalTags());
  }

  @Test
  void sameOrderProducesEqualKeys() {
    MetricKey a = key(tags("region:us-east-1", "tenant_id:acme"));
    MetricKey b = key(tags("region:us-east-1", "tenant_id:acme"));
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void differentValuesProduceDifferentKeys() {
    MetricKey a = key(tags("region:us-east-1"));
    MetricKey b = key(tags("region:eu-west-1"));
    assertNotEquals(a, b);
  }

  @Test
  void differentTagSetsProduceDifferentKeys() {
    MetricKey a = key(tags("region:us-east-1"));
    MetricKey b = key(tags("region:us-east-1", "tenant_id:acme"));
    assertNotEquals(a, b);
  }

  @Test
  void keyWithAdditionalTagsDiffersFromKeyWithout() {
    MetricKey a = key(emptyList());
    MetricKey b = key(tags("region:us-east-1"));
    assertNotEquals(a, b);
  }

  private static List<UTF8BytesString> tags(String... entries) {
    List<UTF8BytesString> list = new ArrayList<>(entries.length);
    for (String e : entries) {
      list.add(UTF8BytesString.create(e));
    }
    return list;
  }

  private static MetricKey key(List<UTF8BytesString> additionalTags) {
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
        additionalTags);
  }
}
