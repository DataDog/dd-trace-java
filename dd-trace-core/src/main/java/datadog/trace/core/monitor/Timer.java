package datadog.trace.core.monitor;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.timgroup.statsd.StatsDClient;
import java.util.Arrays;
import org.HdrHistogram.PackedHistogram;

/**
 * A timer which records times in a histogram, and flushes stats from the histogram after a
 * configurable period of time.
 */
public class Timer extends Recording {

  private static final long THIRTY_SECONDS_AS_NANOS = SECONDS.toNanos(30);

  private static final String[] MEAN = new String[] {"stat:avg"};
  private static final String[] P_50 = new String[] {"stat:p50"};
  private static final String[] P_99 = new String[] {"stat:p99"};
  private static final String[] MAX = new String[] {"stat:max"};

  private final String name;
  private final StatsDClient statsd;
  private final PackedHistogram histogram;
  private final long flushAfterNanos;

  private final String[] meanTags;
  private final String[] p50Tags;
  private final String[] p99Tags;
  private final String[] maxTags;

  private long start;
  private long lastFlush = 0;

  Timer(final String name, final String[] tags, final StatsDClient statsd, long flushAfterNanos) {
    this.name = name;
    this.statsd = statsd;
    this.flushAfterNanos = flushAfterNanos;
    this.histogram = new PackedHistogram(THIRTY_SECONDS_AS_NANOS, 3);
    this.meanTags = mergeTags(MEAN, tags);
    this.p50Tags = mergeTags(P_50, tags);
    this.p99Tags = mergeTags(P_99, tags);
    this.maxTags = mergeTags(MAX, tags);
  }

  Timer(final String name, final StatsDClient statsd, long flushAfterNanos) {
    this(name, null, statsd, flushAfterNanos);
  }

  public Recording start() {
    start = System.nanoTime();
    return this;
  }

  public void reset() {
    long now = System.nanoTime();
    record(now);
    start = now;
  }

  public void stop() {
    record(System.nanoTime());
  }

  private void record(long now) {
    // if it's longer than 30s, we have bigger problems
    histogram.recordValue(Math.min(now - start, THIRTY_SECONDS_AS_NANOS));
    if (now - lastFlush > flushAfterNanos) {
      lastFlush = now;
      flush();
    }
  }

  public void flush() {
    statsd.time(name, (long) histogram.getMean(), meanTags);
    statsd.time(name, histogram.getValueAtPercentile(0.5), p50Tags);
    statsd.time(name, histogram.getValueAtPercentile(0.99), p99Tags);
    statsd.time(name, histogram.getMaxValue(), maxTags);
  }

  private static String[] mergeTags(String[] left, String[] right) {
    if (null == right) {
      return left;
    }
    String[] merged = Arrays.copyOf(left, left.length + right.length);
    System.arraycopy(right, 0, merged, left.length, right.length);
    return merged;
  }
}
