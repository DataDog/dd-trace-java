package datadog.metrics.api.statsd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StatsDCountReporterTest {

  private enum TestCounters implements StatsDCounterKey {
    FOO("test.counters.foo"),
    BAR("test.counters.bar");

    private final String metricName;

    TestCounters(String metricName) {
      this.metricName = metricName;
    }

    @Override
    public String getMetricName() {
      return metricName;
    }
  }

  @Test
  void reportsNonZeroCounters() {
    RecordingStatsDClient client = new RecordingStatsDClient();
    long[] counts = new long[TestCounters.values().length];
    counts[TestCounters.FOO.ordinal()] = 3L;

    StatsDCountReporter.report(client, TestCounters.values(), counts);

    assertEquals(1, client.counts.size());
    assertEquals("test.counters.foo", client.counts.get(0).metricName);
    assertEquals(3L, client.counts.get(0).delta);
  }

  @Test
  void skipsZeroDeltaCounters() {
    RecordingStatsDClient client = new RecordingStatsDClient();
    long[] counts = new long[TestCounters.values().length];
    counts[TestCounters.FOO.ordinal()] = 0L;
    counts[TestCounters.BAR.ordinal()] = 5L;

    StatsDCountReporter.report(client, TestCounters.values(), counts);

    assertEquals(1, client.counts.size());
    assertEquals("test.counters.bar", client.counts.get(0).metricName);
  }

  @Test
  void reportsNegativeDeltas() {
    RecordingStatsDClient client = new RecordingStatsDClient();
    long[] counts = new long[TestCounters.values().length];
    counts[TestCounters.FOO.ordinal()] = -2L;

    StatsDCountReporter.report(client, TestCounters.values(), counts);

    assertEquals(1, client.counts.size());
    assertEquals(-2L, client.counts.get(0).delta);
  }

  @Test
  void passesTagsThrough() {
    RecordingStatsDClient client = new RecordingStatsDClient();
    long[] counts = new long[TestCounters.values().length];
    counts[TestCounters.FOO.ordinal()] = 1L;

    StatsDCountReporter.report(client, TestCounters.values(), counts, "env:test", "service:foo");

    assertArrayEquals(new String[] {"env:test", "service:foo"}, client.counts.get(0).tags);
  }

  @Test
  void reportsNothingWhenAllCountersAreZero() {
    RecordingStatsDClient client = new RecordingStatsDClient();
    long[] counts = new long[TestCounters.values().length];

    StatsDCountReporter.report(client, TestCounters.values(), counts);

    assertTrue(client.counts.isEmpty());
  }
}
