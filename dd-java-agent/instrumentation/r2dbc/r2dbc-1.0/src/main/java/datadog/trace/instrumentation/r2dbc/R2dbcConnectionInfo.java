package datadog.trace.instrumentation.r2dbc;

/**
 * Holds connection metadata and the original SQL string associated with an R2DBC Statement. Used
 * for span tagging and DBM SQL comment injection.
 */
public final class R2dbcConnectionInfo {
  private final String sql;
  private final String dbType;
  private final String dbInstance;
  private final String dbUser;
  private final String dbHostname;

  private R2dbcConnectionInfo(
      String sql, String dbType, String dbInstance, String dbUser, String dbHostname) {
    this.sql = sql;
    this.dbType = dbType;
    this.dbInstance = dbInstance;
    this.dbUser = dbUser;
    this.dbHostname = dbHostname;
  }

  public String getSql() {
    return sql;
  }

  public String getDbType() {
    return dbType;
  }

  public String getDbInstance() {
    return dbInstance;
  }

  public String getDbUser() {
    return dbUser;
  }

  public String getDbHostname() {
    return dbHostname;
  }

  /** Creates an info object with only the SQL string (no connection metadata). */
  public static R2dbcConnectionInfo ofSql(String sql) {
    return new R2dbcConnectionInfo(sql, null, null, null, null);
  }

  /** Creates an info object with SQL string and connection metadata. */
  public static R2dbcConnectionInfo of(
      String sql, String dbType, String dbInstance, String dbUser, String dbHostname) {
    return new R2dbcConnectionInfo(sql, dbType, dbInstance, dbUser, dbHostname);
  }
}
