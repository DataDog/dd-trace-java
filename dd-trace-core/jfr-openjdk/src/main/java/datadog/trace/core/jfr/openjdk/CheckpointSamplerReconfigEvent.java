package datadog.trace.core.jfr.openjdk;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name("datadog.CheckpointSamplerReconfig")
@Label("Checkpoint Sampler Reconfiguration")
@Description("Datadog event corresponding to a (periodic) checkpoint sampler re-configuration.")
@Category("Datadog")
@StackTrace(false)
public class CheckpointSamplerReconfigEvent extends Event {
  @Label("totalCount")
  private final long totalCount;

  @Label("sampledCount")
  private final long sampledCount;

  @Label("budget")
  private final long budget;

  @Label("totalAverage")
  private final double totalAverage;

  @Label("probability")
  private final double probability;

  public CheckpointSamplerReconfigEvent(
      long totalCount, long sampledCount, long budget, double totalAverage, double probability) {
    this.totalCount = totalCount;
    this.sampledCount = sampledCount;
    this.budget = budget;
    this.totalAverage = totalAverage;
    this.probability = probability;
  }
}
