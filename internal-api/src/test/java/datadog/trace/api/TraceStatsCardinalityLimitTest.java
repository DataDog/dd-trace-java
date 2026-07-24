package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class TraceStatsCardinalityLimitTest {

  private static final int DEFAULT = 1024;

  private static Config configWith(String limit) {
    Properties props = new Properties();
    props.setProperty("trace.stats.resource.cardinality.limit", limit);
    return Config.get(props);
  }

  @Test
  void positiveValueIsUsed() {
    assertEquals(256, configWith("256").getTraceStatsCardinalityLimit("resource", DEFAULT));
  }

  @Test
  void zeroFallsBackToDefault() {
    assertEquals(DEFAULT, configWith("0").getTraceStatsCardinalityLimit("resource", DEFAULT));
  }

  @Test
  void negativeFallsBackToDefault() {
    assertEquals(DEFAULT, configWith("-5").getTraceStatsCardinalityLimit("resource", DEFAULT));
  }
}
