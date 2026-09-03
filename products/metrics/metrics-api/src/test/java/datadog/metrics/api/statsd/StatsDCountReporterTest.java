package datadog.metrics.api.statsd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatsDCountReporterTest {

  private static final String[] TAG_A = {"env:a"};
  private static final String[] TAG_B = {"env:b"};

  enum Counters implements StatsDCounterKey {
    FOO("foo.total", TAG_A),
    BAR("bar.total", TAG_A),
    SHARED_A("shared.total", TAG_A),
    SHARED_B("shared.total", TAG_B);

    private final String metricName;
    private final String[] tags;

    Counters(String metricName, String[] tags) {
      this.metricName = metricName;
      this.tags = tags;
    }

    @Override
    public String getMetricName() {
      return metricName;
    }

    @Override
    public String[] getTags() {
      return tags;
    }
  }

  @Test
  void reportsNonZeroCounterWithItsOwnMetricNameAndTags() {
    RecordingStatsDClient statsD = new RecordingStatsDClient();
    Map<Counters, Long> deltas = new HashMap<>();
    deltas.put(Counters.FOO, 3L);

    StatsDCountReporter.report(statsD, Counters.values(), c -> deltas.getOrDefault(c, 0L));

    assertEquals(1, statsD.counts.size());
    RecordingStatsDClient.Count count = statsD.counts.get(0);
    assertEquals("foo.total", count.metricName);
    assertEquals(3L, count.delta);
    assertArrayEquals(TAG_A, count.tags);
  }

  @Test
  void skipsZeroDeltaCounters() {
    RecordingStatsDClient statsD = new RecordingStatsDClient();

    StatsDCountReporter.report(statsD, Counters.values(), c -> 0L);

    assertTrue(statsD.counts.isEmpty());
  }

  @Test
  void reportsNothingWhenEveryCounterIsZero() {
    RecordingStatsDClient statsD = new RecordingStatsDClient();
    Map<Counters, Long> deltas = new HashMap<>();

    StatsDCountReporter.report(statsD, Counters.values(), c -> deltas.getOrDefault(c, 0L));

    assertTrue(statsD.counts.isEmpty());
  }

  @Test
  void reportsConstantsSharingAMetricNameIndependentlyByTag() {
    RecordingStatsDClient statsD = new RecordingStatsDClient();
    Map<Counters, Long> deltas = new HashMap<>();
    deltas.put(Counters.SHARED_A, 5L);
    deltas.put(Counters.SHARED_B, 7L);

    StatsDCountReporter.report(statsD, Counters.values(), c -> deltas.getOrDefault(c, 0L));

    assertEquals(2, statsD.counts.size());
    RecordingStatsDClient.Count a = statsD.counts.get(0);
    RecordingStatsDClient.Count b = statsD.counts.get(1);
    assertEquals("shared.total", a.metricName);
    assertEquals(5L, a.delta);
    assertArrayEquals(TAG_A, a.tags);
    assertEquals("shared.total", b.metricName);
    assertEquals(7L, b.delta);
    assertArrayEquals(TAG_B, b.tags);
  }
}
