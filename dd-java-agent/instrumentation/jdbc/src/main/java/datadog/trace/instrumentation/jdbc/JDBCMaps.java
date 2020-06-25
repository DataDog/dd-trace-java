package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.WeakMap.Provider.newWeakMap;

import datadog.trace.bootstrap.WeakMap;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * JDBC instrumentation shares a global map of connection info.
 *
 * <p>Should be injected into the bootstrap classpath.
 */
public class JDBCMaps {
  public static final WeakMap<Connection, DBInfo> connectionInfo = newWeakMap();
  public static final WeakMap<String, UTF8BytesString> preparedStatementsSql = newWeakMap();
  public static final WeakMap<PreparedStatement, UTF8BytesString> preparedStatements = newWeakMap();
}
