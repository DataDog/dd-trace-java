package datadog.trace.api.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WafMetricCollectorContextClosedRaceTest {

  @Test
  void reportsContextClosedRaceCount() {
    WafMetricCollector collector = WafMetricCollector.get();

    collector.wafContextClosedRace();
    collector.wafContextClosedRace();
    collector.prepareMetrics();

    Collection<WafMetricCollector.WafMetric> metrics = collector.drain();
    Optional<WafMetricCollector.WafMetric> raceMetric =
        metrics.stream().filter(m -> "waf.context_closed_race".equals(m.metricName)).findFirst();

    assertTrue(raceMetric.isPresent(), "expected waf.context_closed_race to be reported");
    assertEquals("count", raceMetric.get().type);
    assertEquals("appsec", raceMetric.get().namespace);
    assertEquals(2L, raceMetric.get().value.longValue());
  }
}
