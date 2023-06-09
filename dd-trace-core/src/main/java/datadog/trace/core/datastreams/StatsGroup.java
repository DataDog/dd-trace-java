package datadog.trace.core.datastreams;

import com.datadoghq.sketch.ddsketch.DDSketch;
import datadog.trace.core.histogram.Histograms;
import java.util.List;

public class StatsGroup {
  private static final double NANOSECONDS_TO_SECOND = 1_000_000_000d;

  private final List<String> edgeTags;
  private final long hash;
  private final long parentHash;
  private final DDSketch pathwayLatency;
  private final DDSketch edgeLatency;
  private long fanIns;
  private long fanOuts;
  private long dropped;

  public StatsGroup(List<String> edgeTags, long hash, long parentHash) {
    this.edgeTags = edgeTags;
    this.hash = hash;
    this.parentHash = parentHash;
    pathwayLatency = Histograms.newHistogram();
    edgeLatency = Histograms.newHistogram();
    fanIns = 0;
    fanOuts = 0;
    dropped = 0;
  }

  public void add(long pathwayLatencyNano, long edgeLatencyNano, boolean isFanIn, boolean isFanOut, boolean isDropped, boolean ignoreLatencies) {
    if (!ignoreLatencies) {
      pathwayLatency.accept(((double) pathwayLatencyNano) / NANOSECONDS_TO_SECOND);
      edgeLatency.accept(((double) edgeLatencyNano) / NANOSECONDS_TO_SECOND);
    }
    if (isFanIn) {
      fanIns++;
    }
    if (isFanOut) {
      fanOuts++;
    }
    if (isDropped) {
      dropped++;
    }
  }

  public List<String> getEdgeTags() {
    return edgeTags;
  }

  public long getHash() {
    return hash;
  }

  public long getParentHash() {
    return parentHash;
  }

  public DDSketch getPathwayLatency() {
    return pathwayLatency;
  }

  public DDSketch getEdgeLatency() {
    return edgeLatency;
  }

  @Override
  public String toString() {
    return "StatsGroup{"
        + "edgeTags='"
        + edgeTags
        + '\''
        + ", hash="
        + hash
        + ", parentHash="
        + parentHash
        + '}';
  }
}
