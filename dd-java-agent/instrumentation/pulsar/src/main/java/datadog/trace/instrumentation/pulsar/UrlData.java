package datadog.trace.instrumentation.pulsar;

public class UrlData {
  private final String host;
  private final Integer port;

  UrlData(String host, Integer port) {
    this.host = host;
    this.port = port;
  }

  public String getHost() {
    return host;
  }

  public Integer getPort() {
    return port;
  }
}
