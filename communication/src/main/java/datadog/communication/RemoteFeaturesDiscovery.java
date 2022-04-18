package datadog.communication;

public interface RemoteFeaturesDiscovery {

  void discover();

  String getTraceEndpoint();
}
