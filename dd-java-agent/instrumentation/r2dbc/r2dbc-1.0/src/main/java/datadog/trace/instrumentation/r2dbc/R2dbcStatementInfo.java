package datadog.trace.instrumentation.r2dbc;

/**
 * Combines SQL statement text and connection metadata for a single R2DBC Statement. Stored in the
 * context store keyed by Statement to make both the query and connection metadata available at
 * execution time.
 */
public final class R2dbcStatementInfo {
  private final String sql;
  private final R2dbcConnectionInfo connectionInfo;

  public R2dbcStatementInfo(String sql, R2dbcConnectionInfo connectionInfo) {
    this.sql = sql;
    this.connectionInfo = connectionInfo;
  }

  public String getSql() {
    return sql;
  }

  public R2dbcConnectionInfo getConnectionInfo() {
    return connectionInfo;
  }
}
