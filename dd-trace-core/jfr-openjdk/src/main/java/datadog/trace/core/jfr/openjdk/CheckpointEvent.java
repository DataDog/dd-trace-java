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
  private final String traceId1;

  @Label("Span Id")
  private final String spanId1;

  @Label("Flags")
  private final int flags;

  public CheckpointEvent(long traceId, long spanId, int flags) {
    this.traceId1 = String.format("%1$32s", Long.toHexString(traceId)).replace(' ', '0');
    this.spanId1 = String.format("%1$32s", Long.toHexString(spanId)).replace(' ', '0');
    this.flags = flags;
  }
}
