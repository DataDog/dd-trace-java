package datadog.trace.core.datastreams;

import datadog.trace.core.histogram.Histogram;
import datadog.trace.core.histogram.HistogramFactory;
import datadog.trace.core.histogram.Histograms;

public class StatsGroup {
  private static final double NANOSECONDS_TO_SECOND = 1_000_000_000d;
  private static final HistogramFactory HISTOGRAM_FACTORY = Histograms.newHistogramFactory();

  private final String type;
  private final String group;
  private final String topic;
  private final long hash;
  private final long parentHash;
  private final Histogram pathwayLatency;
  private final Histogram edgeLatency;

  public StatsGroup(String type, String group, String topic, long hash, long parentHash) {
    this.type = type;
    this.group = group;
    this.topic = topic;
    this.hash = hash;
    this.parentHash = parentHash;
    pathwayLatency = HISTOGRAM_FACTORY.newHistogram();
    edgeLatency = HISTOGRAM_FACTORY.newHistogram();
  }

  public void add(long pathwayLatencyNano, long edgeLatencyNano) {
    pathwayLatency.accept(((double) pathwayLatencyNano) / NANOSECONDS_TO_SECOND);
    edgeLatency.accept(((double) edgeLatencyNano) / NANOSECONDS_TO_SECOND);
  }

  public String getType() {
    return type;
  }

  public String getGroup() {
    return group;
  }

  public String getTopic() {
    return topic;
  }

  public long getHash() {
    return hash;
  }

  public long getParentHash() {
    return parentHash;
  }

  public Histogram getPathwayLatency() {
    return pathwayLatency;
  }

  public Histogram getEdgeLatency() {
    return edgeLatency;
  }
}
