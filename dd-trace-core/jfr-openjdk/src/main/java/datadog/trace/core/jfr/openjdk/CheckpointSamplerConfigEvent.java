package datadog.trace.core.jfr.openjdk;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

@Name("datadog.CheckpointSamplerConfig")
@Label("Checkpoint Sampler Configuration")
@Description("Datadog event publishing the checkpoint sampler re-configuration.")
@Category("Datadog")
@StackTrace(false)
@Period("endChunk")
public class CheckpointSamplerConfigEvent extends Event {
  @Label("samplerWindow")
  @Timespan("MILLISECONDS")
  private final long samplerWindow;

  @Label("samplesPerWindow")
  private final int sammplesPerWindow;

  @Label("averageLookback")
  private final int averageLookback;

  @Label("budgetLookback")
  private final int budgetLookback;

  public CheckpointSamplerConfigEvent(
      long samplerWindow, int sammplesPerWindow, int averageLookback, int budgetLookback) {
    this.samplerWindow = samplerWindow;
    this.sammplesPerWindow = sammplesPerWindow;
    this.averageLookback = averageLookback;
    this.budgetLookback = budgetLookback;
  }
}
