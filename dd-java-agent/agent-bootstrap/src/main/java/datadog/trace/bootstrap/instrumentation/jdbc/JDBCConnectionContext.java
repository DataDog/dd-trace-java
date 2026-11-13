package datadog.trace.bootstrap.instrumentation.jdbc;

/** Immutable database metadata and mutable state scoped to one JDBC connection. */
public final class JDBCConnectionContext {
  public static final JDBCConnectionContext DEFAULT = new JDBCConnectionContext(DBInfo.DEFAULT);

  private final DBInfo dbInfo;
  private volatile String poolName;
  private volatile boolean oracleServiceActionUnsupported;

  public JDBCConnectionContext(DBInfo dbInfo) {
    this.dbInfo = dbInfo;
  }

  public DBInfo getDbInfo() {
    return dbInfo;
  }

  public String getPoolName() {
    return poolName;
  }

  public void setPoolName(String poolName) {
    this.poolName = poolName;
  }

  /** Returns whether the driver rejected the Oracle ACTION client info for this connection. */
  public boolean isOracleServiceActionUnsupported() {
    return oracleServiceActionUnsupported;
  }

  public void markOracleServiceActionUnsupported() {
    oracleServiceActionUnsupported = true;
  }
}
