package datadog.trace.instrumentation.rediscala;

public class RedisConnectionInfo {
  public final String host;
  public final int port;
  public final int dbIndex;

  public RedisConnectionInfo(String host, int port, int dbIndex) {
    this.host = host;
    this.port = port;
    this.dbIndex = dbIndex;
  }
}
