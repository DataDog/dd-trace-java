package datadog.trace.common.writer;

public interface RemoteMapperDiscovery {
  void discover();

  RemoteMapper getMapper();

  /**
   * Whether the mapper {@link #getMapper()} returned no longer suits the destination, so the caller
   * should send what it has encoded and {@link #discover()} again. Called before encoding each
   * trace, so it must be cheap.
   */
  default boolean isOutdated() {
    return false;
  }
}
