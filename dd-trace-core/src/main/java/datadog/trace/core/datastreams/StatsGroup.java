package datadog.trace.core.datastreams;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.mapping.IndexMapping;
import com.datadoghq.sketch.ddsketch.mapping.LogarithmicMapping;
import com.datadoghq.sketch.ddsketch.store.UnboundedSizeDenseStore;

public class StatsGroup {
  private static final IndexMapping SKETCH_MAPPING = new LogarithmicMapping(0.01);
  private static final double NANOSECONDS_TO_SECOND = 1_000_000_000d;

  private final String edge;
  private final long hash;
  private final long parentHash;
  private final DDSketch pathwayLatency;
  private final DDSketch edgeLatency;

  public StatsGroup(String edge, long hash, long parentHash) {
    this.edge = edge;
    this.hash = hash;
    this.parentHash = parentHash;
    pathwayLatency = DDSketch.of(SKETCH_MAPPING, new UnboundedSizeDenseStore(), new UnboundedSizeDenseStore(), 0);
    edgeLatency = DDSketch.of(SKETCH_MAPPING, new UnboundedSizeDenseStore(), new UnboundedSizeDenseStore(), 0);
  }

  public void add(long pathwayLatencyNano, long edgeLatencyNano) {
    pathwayLatency.accept(((double) pathwayLatencyNano) / NANOSECONDS_TO_SECOND);
    edgeLatency.accept(((double) edgeLatencyNano) / NANOSECONDS_TO_SECOND);
  }

  public String getEdge() {
    return edge;
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
}
