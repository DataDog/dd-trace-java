package datadog.trace.common.writer;

public interface RemoteMapperDiscovery {
  void discover();

  RemoteMapper getMapper();

  /**
   * Whether the mapper {@link #getMapper()} returned no longer suits the destination, so the caller
   * should {@link #discover()} again before its next payload. Called before encoding the first
   * trace of each payload.
   */
  default boolean isOutdated() {
    return false;
  }
}
