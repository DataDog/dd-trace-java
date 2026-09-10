package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class TraceStatsCardinalityLimitTest {

  private static final int DEFAULT = 1024;
  // Mirrors Config.MAX_TRACE_STATS_CARDINALITY_LIMIT (package-private, not visible here).
  private static final int MAX = 1 << 16;

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

  @Test
  void valueAtMaxIsUsed() {
    // 1 << 16 is the largest accepted value; it is used verbatim.
    assertEquals(
        MAX, configWith(Integer.toString(MAX)).getTraceStatsCardinalityLimit("resource", DEFAULT));
  }

  @Test
  void valueAboveMaxFallsBackToDefault() {
    // One past the cap falls back rather than sizing an oversized handler table.
    assertEquals(
        DEFAULT,
        configWith(Integer.toString(MAX + 1)).getTraceStatsCardinalityLimit("resource", DEFAULT));
  }

  @Test
  void heapExhaustingValueFallsBackToDefault() {
    // A value below TagCardinalityHandler's own 1<<29 guard but large enough to allocate
    // multi-gigabyte handler tables must not reach the handler; it falls back to the default.
    assertEquals(
        DEFAULT, configWith("500000000").getTraceStatsCardinalityLimit("resource", DEFAULT));
  }
}
