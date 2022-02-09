package datadog.trace.core.datastreams;

public class StatsPoint {
    private final String service;
    private final String edge;
    private final long hash;
    private final long parentHash;
    private final long timestampMillis;
    private final long pathwayLatencyNano;
    private final long edgeLatencyNano;

    public StatsPoint(String service, String edge, long hash, long parentHash, long timestampMillis, long pathwayLatencyNano, long edgeLatencyNano) {
        this.service = service;
        this.edge = edge;
        this.hash = hash;
        this.parentHash = parentHash;
        this.timestampMillis = timestampMillis;
        this.pathwayLatencyNano = pathwayLatencyNano;
        this.edgeLatencyNano = edgeLatencyNano;
    }

    public String getService() {
        return service;
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

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public long getPathwayLatencyNano() {
        return pathwayLatencyNano;
    }

    public long getEdgeLatencyNano() {
        return edgeLatencyNano;
    }
}
