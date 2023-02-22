package datadog.trace.instrumentation.jdbc;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.SqlInjectionModule;

@CallSite(spi = IastAdvice.class)
public class IastStatementCallSite {

  @CallSite.BeforeArray({
    @CallSite.Before("java.sql.ResultSet java.sql.Statement.executeQuery(java.lang.String)"),
    @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String)"),
    @CallSite.Before("boolean java.sql.Statement.execute(java.lang.String)"),
    @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String, int)"),
    @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String, int[])"),
    @CallSite.Before("int java.sql.Statement.executeUpdate(java.lang.String, java.lang.String[])"),
    @CallSite.Before("boolean java.sql.Statement.execute(java.lang.String, int)"),
    @CallSite.Before("boolean java.sql.Statement.execute(java.lang.String, int[])"),
    @CallSite.Before("boolean java.sql.Statement.execute(java.lang.String, java.lang.String[])"),
    @CallSite.Before("long java.sql.Statement.executeLargeUpdate(java.lang.String)"),
    @CallSite.Before("long java.sql.Statement.executeLargeUpdate(java.lang.String, int)"),
    @CallSite.Before("long java.sql.Statement.executeLargeUpdate(java.lang.String, int[])"),
    @CallSite.Before(
        "long java.sql.Statement.executeLargeUpdate(java.lang.String, java.lang.String[])"),
  })
  public static void beforeExecute(@CallSite.Argument(0) String sql) {
    final SqlInjectionModule module = InstrumentationBridge.SQL_INJECTION;
    if (module != null) {
      try {
        module.onJdbcQuery(sql);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforeExecute threw", e);
      }
    }
  }
}
