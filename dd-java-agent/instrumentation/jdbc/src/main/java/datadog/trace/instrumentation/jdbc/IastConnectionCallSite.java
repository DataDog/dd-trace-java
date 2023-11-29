package datadog.trace.instrumentation.jdbc;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.SqlInjectionModule;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.sql.Connection;

@Sink(VulnerabilityTypes.SQL_INJECTION)
@CallSite(
    spi = IastCallSites.class,
    helpers = {JDBCDecorator.class})
public class IastConnectionCallSite {

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
        final DBInfo dbInfo = JDBCDecorator.parseDBInfoFromConnection(conn);
        module.onJdbcQuery(sql, dbInfo.getType());
      } catch (final Throwable e) {
        module.onUnexpectedException("beforePrepare threw", e);
      }
    }
  }
}
