package datadog.trace.api.metrics;

import java.util.concurrent.atomic.AtomicLong;

/** Telemetry counters for the agentless logs intake. */
public final class LogsIntakeMetrics {
  static final String LOGS_DROPPED = "logs_dropped";
  static final String OVERFULL_BUFFER = "reason:overfull_buffer";

  private static final LogsIntakeMetrics INSTANCE = new LogsIntakeMetrics();

  private final TaggedCounter droppedLogs = new TaggedCounter(LOGS_DROPPED, OVERFULL_BUFFER);

  public static LogsIntakeMetrics getInstance() {
    return INSTANCE;
  }

  private LogsIntakeMetrics() {}

  /** Records a log message dropped because the intake queue was full. */
  public void onLogDropped() {
    this.droppedLogs.counter.incrementAndGet();
  }

  public TaggedCounter getDroppedLogs() {
    return this.droppedLogs;
  }

  /** A named, single-tag counter drained as a telemetry {@code count} metric. */
  public static final class TaggedCounter implements CoreCounter {
    private final String name;
    private final String tag;
    private final AtomicLong counter = new AtomicLong();
    private long previousCount;

    TaggedCounter(String name, String tag) {
      this.name = name;
      this.tag = tag;
    }

    @Override
    public String getName() {
      return this.name;
    }

    public String getTag() {
      return this.tag;
    }

    @Override
    public long getValue() {
      return this.counter.get();
    }

    @Override
    public long getValueAndReset() {
      long count = this.counter.get();
      long delta = count - this.previousCount;
      this.previousCount = count;
      return delta;
    }
  }
}
