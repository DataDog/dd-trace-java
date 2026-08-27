package datadog.trace.api.debugger;

import datadog.trace.api.telemetry.MetricCollector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLongArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebuggerMetricCollector
    implements MetricCollector<DebuggerMetricCollector.DebuggerMetric> {
  private static final Logger LOGGER = LoggerFactory.getLogger(DebuggerMetricCollector.class);
  public static final DebuggerMetricCollector INSTANCE = new DebuggerMetricCollector();

  interface Reason {
    String getTag();
  }

  public enum DroppedReason implements Reason {
    QUEUE_FULL("reason:queueFull"),
    PAYLOAD_TOO_LARGE("reason:payloadTooLarge"),
    ;

    private final String tag;

    DroppedReason(String tag) {
      this.tag = tag;
    }

    @Override
    public String getTag() {
      return tag;
    }
  }

  public enum SkippedReason implements Reason {
    RATE_LIMIT("reason:rateLimitProbe"),
    EVALUATION_TIME_OUT("reason:evaluationTimeOut"),
    ;

    private final String tag;

    SkippedReason(String tag) {
      this.tag = tag;
    }

    @Override
    public String getTag() {
      return tag;
    }
  }

  private final BlockingQueue<DebuggerMetric> metricsQueue =
      new ArrayBlockingQueue<>(RAW_QUEUE_SIZE);
  private final AtomicLongArray eventDroppedCounters =
      new AtomicLongArray(DroppedReason.values().length);
  private final AtomicLongArray eventSkippedCounters =
      new AtomicLongArray(SkippedReason.values().length);

  public static DebuggerMetricCollector get() {
    return INSTANCE;
  }

  public void recordEventDropped(DroppedReason reason) {
    eventDroppedCounters.incrementAndGet(reason.ordinal());
  }

  public void recordEventSkipped(SkippedReason reason) {
    eventSkippedCounters.incrementAndGet(reason.ordinal());
  }

  @Override
  public void prepareMetrics() {
    addCounterMetric(eventDroppedCounters, "events.dropped", DroppedReason.values());
    addCounterMetric(eventSkippedCounters, "events.skipped", SkippedReason.values());
  }

  private <E extends Enum<E> & Reason> void addCounterMetric(
      AtomicLongArray counters, String name, E[] enumValues) {
    for (E enumValue : enumValues) {
      // get and reset
      long value = counters.getAndSet(enumValue.ordinal(), 0);
      if (value > 0) {
        metricsQueue.offer(new DebuggerMetric(name, value, enumValue.getTag()));
      }
    }
  }

  @Override
  public Collection<DebuggerMetric> drain() {
    if (metricsQueue.isEmpty()) {
      return Collections.emptyList();
    }
    List<DebuggerMetric> metrics = new ArrayList<>();
    metricsQueue.drainTo(metrics);
    return metrics;
  }

  public static class DebuggerMetric extends MetricCollector.Metric {

    private static final String NAMESPACE = "live_debugger";

    public DebuggerMetric(String metricName, long value, String... tags) {
      super(NAMESPACE, true, metricName, "count", value, tags);
    }
  }
}
