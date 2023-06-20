package datadog.trace.instrumentation.couchbase_31.client;

public class TracingInfo {
  private String seedNodes;
  private String peerService;

  public TracingInfo(String seedNodes, String peerService) {
    this.seedNodes = seedNodes;
    this.peerService = peerService;
  }

  public String getSeedNodes() {
    return seedNodes;
  }

  public void setSeedNodes(String seedNodes) {
    this.seedNodes = seedNodes;
  }

  public String getPeerService() {
    return peerService;
  }

  public void setPeerService(String peerService) {
    this.peerService = peerService;
  }
}
