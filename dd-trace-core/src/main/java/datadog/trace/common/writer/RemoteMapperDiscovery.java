package datadog.trace.common.writer;

public interface RemoteMapperDiscovery {
  void discover();

  RemoteMapper getMapper();
}
