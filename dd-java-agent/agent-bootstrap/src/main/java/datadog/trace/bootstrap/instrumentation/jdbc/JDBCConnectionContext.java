package datadog.trace.bootstrap.instrumentation.jdbc;

/** Immutable database metadata and mutable state scoped to one JDBC connection. */
public final class JDBCConnectionContext {
  public static final JDBCConnectionContext DEFAULT = new JDBCConnectionContext(DBInfo.DEFAULT);

  private final DBInfo dbInfo;
  private volatile String poolName;
  private volatile String oracleServiceHash;
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

  /** Returns whether this connection needs the given Oracle service hash. */
  public boolean shouldSetOracleServiceHash(String baseHash) {
    return !oracleServiceActionUnsupported && !baseHash.equals(oracleServiceHash);
  }

  /** Returns whether the given Oracle service hash was successfully applied to this connection. */
  public boolean isOracleServiceHashSet(String baseHash) {
    return baseHash.equals(oracleServiceHash);
  }

  /** Records a successfully applied Oracle service hash. */
  public void markOracleServiceHashSet(String baseHash) {
    oracleServiceHash = baseHash;
  }

  public void markOracleServiceActionUnsupported() {
    oracleServiceActionUnsupported = true;
  }
}
