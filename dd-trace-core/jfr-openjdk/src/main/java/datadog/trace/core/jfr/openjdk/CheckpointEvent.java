package datadog.trace.core.jfr.openjdk;

import static datadog.trace.api.Checkpointer.CPU;
import static datadog.trace.api.Checkpointer.SPAN;
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION;

import datadog.trace.core.util.SystemAccess;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

@Name("datadog.Checkpoint")
@Label("Checkpoint")
@Description("Datadog event corresponding to a tracing checkpoint.")
@Category("Datadog")
@StackTrace(false)
public class CheckpointEvent extends Event {

  private static final int RECORD_CPU_TIME = CPU | SPAN | THREAD_MIGRATION;

  @Label("Trace Id")
  private final long traceId;

  @Label("Span Id")
  private final long spanId;

  @Label("Flags")
  private final int flags;

  @Label("Thread CPU Time")
  @Timespan
  private final long cpuTime;

  public CheckpointEvent(long traceId, long spanId, int flags) {
    this.traceId = traceId;
    this.spanId = spanId;
    this.flags = flags;
    if ((flags & RECORD_CPU_TIME) != 0 && isEnabled()) {
      this.cpuTime = SystemAccess.getCurrentThreadCpuTime();
    } else {
      this.cpuTime = 0L;
    }
  }
}
