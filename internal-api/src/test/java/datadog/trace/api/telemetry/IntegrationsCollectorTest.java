package datadog.trace.api.telemetry;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntegrationsCollectorTest extends DDJavaSpecification {

  @Test
  void updateDrainIntegrations() {
    // setup
    IntegrationsCollector collector = IntegrationsCollector.get();
    collector.update(singletonList("spring"), true);
    collector.update(asList("netty", "jdbc"), false);

    // when
    collector.update(asList("netty", "jetty"), true);

    // then
    Map<String, Boolean> expected = new HashMap<>();
    expected.put("spring", true);
    expected.put("netty", true);
    expected.put("jdbc", false);
    expected.put("jetty", true);
    assertEquals(expected, collector.drain());
    assertEquals(emptyMap(), collector.drain());
  }
}
