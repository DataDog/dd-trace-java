package datadog.trace.instrumentation.vertx_sql_client;

import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;

public class QueryInfo {
  private final DBInfo dbInfo;
  private final DBQueryInfo dbQueryInfo;

  public QueryInfo(DBInfo dbInfo, DBQueryInfo dbQueryInfo) {
    this.dbInfo = dbInfo;
    this.dbQueryInfo = dbQueryInfo;
  }

  public DBInfo getDbInfo() {
    return dbInfo;
  }

  public DBQueryInfo getDbQueryInfo() {
    return dbQueryInfo;
  }
}
