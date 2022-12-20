package datadog.trace.instrumentation.jdbc;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.SqlInjectionModule;

@CallSite(spi = IastAdvice.class)
public class IastConnectionCallSite {

  @CallSite.BeforeArray({
    @CallSite.Before(
        "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String)"),
    @CallSite.Before(
        "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String, int, int)"),
    @CallSite.Before(
        "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String, int, int, int)"),
    @CallSite.Before(
        "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String, int)"),
    @CallSite.Before(
        "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String, int[])"),
    @CallSite.Before(
        "java.sql.PreparedStatement java.sql.Connection.prepareStatement(java.lang.String, java.lang.String[])"),
    @CallSite.Before(
        "java.sql.CallableStatement java.sql.Connection.prepareCall(java.lang.String)"),
    @CallSite.Before(
        "java.sql.CallableStatement java.sql.Connection.prepareCall(java.lang.String, int, int)"),
    @CallSite.Before(
        "java.sql.CallableStatement java.sql.Connection.prepareCall(java.lang.String, int, int, int)"),
  })
  public static void beforePrepare(@CallSite.Argument(0) final String sql) {
    final SqlInjectionModule module = InstrumentationBridge.SQL_INJECTION;
    if (module != null) {
      try {
        module.onJdbcQuery(sql);
      } catch (final Throwable e) {
        module.onUnexpectedException("beforePrepare threw", e);
      }
    }
  }
}
