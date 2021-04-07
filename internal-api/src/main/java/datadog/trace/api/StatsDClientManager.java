package datadog.trace.api;

public interface StatsDClientManager {
  StatsDClient statsDClient(String host, int port, String namespace, String[] constantTags);
}
