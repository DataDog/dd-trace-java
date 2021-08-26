package datadog.trace.core.jfr.openjdk;

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
  private final long traceId;

  @Label("Span Id")
  private final long spanId;

  @Label("Flags")
  private final int flags;

  public CheckpointEvent(long traceId, long spanId, int flags) {
    this.traceId = traceId;
    this.spanId = spanId;
    this.flags = flags;
  }
}
