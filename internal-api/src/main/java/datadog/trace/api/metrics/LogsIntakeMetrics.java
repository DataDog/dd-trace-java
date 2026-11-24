package datadog.trace.api.metrics;

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
    this.droppedLogs.add(1);
  }

  public TaggedCounter getDroppedLogs() {
    return this.droppedLogs;
  }
}
