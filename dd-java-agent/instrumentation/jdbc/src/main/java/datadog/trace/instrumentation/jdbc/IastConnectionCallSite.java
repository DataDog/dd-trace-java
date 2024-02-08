package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.FieldBackedContextStores.getContextStore;
import static datadog.trace.bootstrap.FieldBackedContextStores.getContextStoreId;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.SqlInjectionModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.sql.Connection;
import javax.annotation.Nonnull;

@Sink(VulnerabilityTypes.SQL_INJECTION)
@CallSite(
    spi = IastCallSites.class,
    helpers = {JDBCDecorator.class})
public class IastConnectionCallSite {

  private static ContextStore<Connection, DBInfo> DB_INFO_STORE = null;

  @SuppressWarnings("unchecked")
  @Nonnull
  public static DBInfo getDBInfo(final Connection connection) {
    if (DB_INFO_STORE == null) {
      final int storeId = getContextStoreId(Connection.class.getName(), DBInfo.class.getName());
      final ContextStore<?, ?> store = getContextStore(storeId);
      DB_INFO_STORE = (ContextStore<Connection, DBInfo>) store;
    }
    if (DB_INFO_STORE == null) {
      return JDBCDecorator.parseDBInfoFromConnection(connection);
    } else {
      return JDBCDecorator.parseDBInfo(connection, DB_INFO_STORE);
    }
  }

  @CallSite.Before(
      "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String)")
  @CallSite.Before(
      "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String, int, int)")
  @CallSite.Before(
      "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String, int, int, int)")
  @CallSite.Before(
      "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String, int)")
  @CallSite.Before(
      "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String, int[])")
  @CallSite.Before(
      "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String, java.lang.String[])")
  @CallSite.Before("java.sql.CallableStatement java.sql.Connection.prepareCall(java.lang.String)")
  @CallSite.Before(
      "java.sql.CallableStatement java.sql.Connection.prepareCall(java.lang.String, int, int)")
  @CallSite.Before(
      "java.sql.CallableStatement java.sql.Connection.prepareCall(java.lang.String, int, int, int)")
  public static void beforePrepare(
      @CallSite.This final Connection conn, @CallSite.Argument(0) final String sql) {
    final SqlInjectionModule module = InstrumentationBridge.SQL_INJECTION;
    if (module != null) {
      try {
        final DBInfo dbInfo = getDBInfo(conn);
        module.onJdbcQuery(sql, dbInfo.getType());
      } catch (final Throwable e) {
        module.onUnexpectedException("beforePrepare threw", e);
      }
    }
  }
}
