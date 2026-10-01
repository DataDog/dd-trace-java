package datadog.trace.instrumentation.r2dbc;

/**
 * Holds connection metadata extracted from an R2DBC Connection for use in span tagging. Captures
 * host, port, user, database name, and database type to support Database Monitoring (DBM) features.
 */
public final class R2dbcConnectionInfo {
  private final String host;
  private final Integer port;
  private final String user;
  private final String dbName;
  private final String dbType;

  public R2dbcConnectionInfo(String host, Integer port, String user, String dbName, String dbType) {
    this.host = host;
    this.port = port;
    this.user = user;
    this.dbName = dbName;
    this.dbType = dbType;
  }

  public String getHost() {
    return host;
  }

  public Integer getPort() {
    return port;
  }

  public String getUser() {
    return user;
  }

  public String getDbName() {
    return dbName;
  }

  public String getDbType() {
    return dbType;
  }
}
