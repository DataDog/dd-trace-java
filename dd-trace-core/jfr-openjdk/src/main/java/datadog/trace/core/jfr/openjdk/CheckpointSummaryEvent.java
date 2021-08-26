package datadog.trace.core.jfr.openjdk;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;

@Name("datadog.CheckpointSummary")
@Label("Checkpoint Summary")
@Description("Checkpoint emission summary")
@Category("Datadog")
@Period("endChunk")
@StackTrace(false)
public class CheckpointSummaryEvent extends Event {
  @Label("Dropped")
  private final long dropped;

  @Label("Emitted")
  private final long emitted;

  @Label("Rate Limit")
  private final int rateLimit;

  public CheckpointSummaryEvent(int rateLimit, long emitted, long dropped) {
    this.rateLimit = rateLimit;
    this.dropped = dropped;
    this.emitted = emitted;
  }
}
