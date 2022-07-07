package datadog.trace.api;

public interface StatsDClientManager {
  StatsDClient statsDClient(
      String host, Integer port, String namedPipe, String namespace, String[] constantTags);
}
