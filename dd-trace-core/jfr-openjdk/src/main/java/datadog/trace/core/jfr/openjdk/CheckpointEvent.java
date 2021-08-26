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

  @Label("Local Root Span Id Id")
  private final long localRootSpanId;

  @Label("Span Id")
  private final long spanId;

  @Label("Flags")
  private final int flags;

  public CheckpointEvent(final long localRootSpanId, final long spanId, final int flags) {
    this.localRootSpanId = localRootSpanId;
    this.spanId = spanId;
    this.flags = flags;
  }
}
