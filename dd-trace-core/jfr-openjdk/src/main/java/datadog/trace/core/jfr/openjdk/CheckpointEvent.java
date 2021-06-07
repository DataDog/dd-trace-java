package datadog.trace.core.jfr.openjdk;

import static datadog.trace.api.Checkpointer.CPU;

import datadog.trace.core.util.SystemAccess;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.Checkpoint")
@Label("Checkpoint")
@Description("Datadog event corresponding to a tracing checkpoint.")
@Category("Datadog")
@StackTrace(false)
public class CheckpointEvent extends Event {

  @Label("Trace Id")
  private final double traceId;

  @Label("Span Id")
  private final double spanId;

  @Label("Flags")
  private final int flags;

  @Label("Thread CPU Time")
  private final long cpuTime;

  public CheckpointEvent(long traceId, long spanId, int flags) {
    this.traceId = Double.longBitsToDouble(traceId);
    this.spanId = Double.longBitsToDouble(spanId);
    this.flags = flags;
    if ((flags & CPU) != 0 && isEnabled()) {
      this.cpuTime = SystemAccess.getCurrentThreadCpuTime();
    } else {
      this.cpuTime = 0L;
    }
  }
}
