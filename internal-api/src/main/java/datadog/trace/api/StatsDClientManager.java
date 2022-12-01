package datadog.trace.api;

public interface StatsDClientManager {
  default StatsDClient statsDClient(
      String host, Integer port, String namedPipe, String namespace, String[] constantTags) {
    return statsDClient(host, port, namedPipe, namespace, constantTags, true);
  }

  StatsDClient statsDClient(
      String host,
      Integer port,
      String namedPipe,
      String namespace,
      String[] constantTags,
      boolean useAggregation);
}
