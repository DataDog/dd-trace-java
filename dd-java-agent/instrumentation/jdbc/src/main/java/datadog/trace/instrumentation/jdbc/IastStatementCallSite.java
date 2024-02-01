package datadog.trace.instrumentation.jdbc;

import static datadog.trace.instrumentation.jdbc.IastConnectionCallSite.getDBInfo;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.SqlInjectionModule;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.sql.Statement;

@Sink(VulnerabilityTypes.SQL_INJECTION)
@CallSite(
    spi = IastCallSites.class,
    helpers = {JDBCDecorator.class})
public class IastStatementCallSite {

  @CallSite.Before("void java.sql.Statement.addBatch(java.lang.String)")
  @CallSite.Before("java.sql.ResultSet java.sql.Statement.executeQuery(java.lang.String)")
  @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String)")
  @CallSite.Before("boolean java.sql.Statement.execute(java.lang.String)")
  @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String, int)")
  @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String, int[])")
  @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String, java.lang.String[])")
  @CallSite.Before("boolean java.sql.Statement.execute(java.lang.String, int)")
  @CallSite.Before("boolean java.sql.Statement.execute(java.lang.String, int[])")
  @CallSite.Before("boolean java.sql.Statement.execute(java.lang.String, java.lang.String[])")
  @CallSite.Before("long java.sql.Statement.executeLargeUpdate(java.lang.String)")
  @CallSite.Before("long java.sql.Statement.executeLargeUpdate(java.lang.String, int)")
  @CallSite.Before("long java.sql.Statement.executeLargeUpdate(java.lang.String, int[])")
  @CallSite.Before(
      "long java.sql.Statement.executeLargeUpdate(java.lang.String, java.lang.String[])")
  public static void beforeExecute(
      @CallSite.This Statement statement, @CallSite.Argument(0) String sql) {
    final SqlInjectionModule module = InstrumentationBridge.SQL_INJECTION;
    if (module != null) {
      try {
        final DBInfo dbInfo = getDBInfo(statement.getConnection());
        module.onJdbcQuery(sql, dbInfo.getType());
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeExecute threw", e);
      }
    }
  }
}
