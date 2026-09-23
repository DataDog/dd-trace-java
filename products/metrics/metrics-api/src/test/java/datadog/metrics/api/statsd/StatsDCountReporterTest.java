package datadog.metrics.api.statsd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.api.Accumulator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

  @Test
  void flushDrainsAndReportsWithoutPerturbingTheCumulativeLiveTotal() {
    RecordingStatsDClient statsD = new RecordingStatsDClient();
    StatsDCountReporter<Counters> reporter = StatsDCountReporter.of(statsD, Counters.class);
    reporter.inc(Counters.FOO);
    reporter.add(Counters.BAR, 4L);

    reporter.flush();

    assertEquals(2, statsD.counts.size());
    // live() is a cumulative total (real events observed), not "since the last flush" -- it
    // doesn't reset just because a flush drained and reported the delta.
    Accumulator.Counts<Counters> live = reporter.live();
    assertEquals(1L, live.get(Counters.FOO));
    assertEquals(4L, live.get(Counters.BAR));

    reporter.flush();

    // a second flush with nothing new to report doesn't inflate the cumulative total either.
    assertEquals(2, statsD.counts.size());
    live = reporter.live();
    assertEquals(1L, live.get(Counters.FOO));
    assertEquals(4L, live.get(Counters.BAR));
  }

  @Test
  void flushCompensatesCountersNotConfirmedDeliveredAfterAnException() {
    List<RecordingStatsDClient.Count> delivered = new ArrayList<>();
    AtomicBoolean failNextBar = new AtomicBoolean(true);
    StatsDClient failsOnBarOnce =
        new StatsDClient() {
          @Override
          public void incrementCounter(String metricName, String... tags) {}

          @Override
          public void count(String metricName, long delta, String... tags) {
            if (metricName.equals("bar.total") && failNextBar.compareAndSet(true, false)) {
              throw new RuntimeException("boom");
            }
            delivered.add(new RecordingStatsDClient.Count(metricName, delta, tags));
          }

          @Override
          public void gauge(String metricName, long value, String... tags) {}

          @Override
          public void gauge(String metricName, double value, String... tags) {}

          @Override
          public void histogram(String metricName, long value, String... tags) {}

          @Override
          public void histogram(String metricName, double value, String... tags) {}

          @Override
          public void distribution(String metricName, long value, String... tags) {}

          @Override
          public void distribution(String metricName, double value, String... tags) {}

          @Override
          public void serviceCheck(
              String serviceCheckName, String status, String message, String... tags) {}

          @Override
          public void error(Exception error) {}

          @Override
          public int getErrorCount() {
            return 0;
          }

          @Override
          public void close() {}
        };

    StatsDCountReporter<Counters> reporter = StatsDCountReporter.of(failsOnBarOnce, Counters.class);
    reporter.inc(Counters.FOO);
    reporter.add(Counters.BAR, 4L);
    reporter.add(Counters.SHARED_A, 2L);

    reporter.flush();

    assertEquals(1, delivered.size());
    assertEquals("foo.total", delivered.get(0).metricName);

    // the cumulative live total counts every real event exactly once, whether or not statsd ever
    // received it -- compensating for the failed send must not inflate this.
    Accumulator.Counts<Counters> live = reporter.live();
    assertEquals(1L, live.get(Counters.FOO));
    assertEquals(4L, live.get(Counters.BAR));
    assertEquals(2L, live.get(Counters.SHARED_A));

    delivered.clear();
    reporter.flush();

    assertEquals(2, delivered.size());
    assertEquals("bar.total", delivered.get(0).metricName);
    assertEquals(4L, delivered.get(0).delta);
    assertEquals("shared.total", delivered.get(1).metricName);
    assertEquals(2L, delivered.get(1).delta);

    // still exactly-once in the cumulative total after the retry succeeds.
    live = reporter.live();
    assertEquals(1L, live.get(Counters.FOO));
    assertEquals(4L, live.get(Counters.BAR));
    assertEquals(2L, live.get(Counters.SHARED_A));
  }
}
