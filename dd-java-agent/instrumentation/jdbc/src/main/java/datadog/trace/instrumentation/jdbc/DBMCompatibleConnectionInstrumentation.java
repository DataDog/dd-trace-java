package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.logQueryInfoInjection;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class DBMCompatibleConnectionInstrumentation extends AbstractConnectionInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  /** Instrumentation class for connections for Database Monitoring supported DBs * */
  public DBMCompatibleConnectionInstrumentation() {
    super("jdbc", "dbm");
  }

  // Classes to cover all currently supported
  // db types for the Database Monitoring product
  static final String[] CONCRETE_TYPES = {
    "com.microsoft.sqlserver.jdbc.SQLServerConnection",
    // jtds (for SQL Server and Sybase)
    "net.sourceforge.jtds.jdbc.ConnectionJDBC2", // 1.2
    "net.sourceforge.jtds.jdbc.JtdsConnection", // 1.3
    // postgresql seems to be complete
    "org.postgresql.jdbc.PgConnection",
    "org.postgresql.jdbc1.Connection",
    "org.postgresql.jdbc1.Jdbc1Connection",
    "org.postgresql.jdbc2.Connection",
    "org.postgresql.jdbc2.Jdbc2Connection",
    "org.postgresql.jdbc3.Jdbc3Connection",
    "org.postgresql.jdbc3g.Jdbc3gConnection",
    "org.postgresql.jdbc4.Jdbc4Connection",
    "postgresql.Connection",
    // EDB version of postgresql
    "com.edb.jdbc.PgConnection",
    // should cover Oracle
    "oracle.jdbc.driver.PhysicalConnection",
    // should cover mysql
    "com.mysql.jdbc.Connection",
    "com.mysql.jdbc.jdbc1.Connection",
    "com.mysql.jdbc.jdbc2.Connection",
    "com.mysql.jdbc.ConnectionImpl",
    "com.mysql.jdbc.JDBC4Connection",
    "com.mysql.cj.jdbc.ConnectionImpl",
    // complete
    "org.mariadb.jdbc.MySQLConnection",
    // MariaDB Connector/J v2.x
    "org.mariadb.jdbc.MariaDbConnection",
    // MariaDB Connector/J v3.x
    "org.mariadb.jdbc.Connection",
    // aws-mysql-jdbc
    "software.aws.rds.jdbc.mysql.shading.com.mysql.cj.jdbc.ConnectionImpl",
    // for testing purposes
    "test.TestConnection"
  };

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JDBCDecorator", packageName + ".SQLCommenter",
    };
  }

  @Override
  public String[] knownMatchingTypes() {
    return CONCRETE_TYPES;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        nameStartsWith("prepare")
            .and(takesArgument(0, String.class))
            // Also include CallableStatement, which is a subtype of PreparedStatement
            .and(returns(hasInterface(named("java.sql.PreparedStatement")))),
        DBMCompatibleConnectionInstrumentation.class.getName() + "$ConnectionAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("java.sql.Statement", DBQueryInfo.class.getName());
    contextStore.put("java.sql.Connection", DBInfo.class.getName());
    return contextStore;
  }

  public static class ConnectionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static String onEnter(
        @Advice.This Connection connection,
        @Advice.Argument(value = 0, readOnly = false) String sql) {
      //      Using INJECT_COMMENT fails to update when a test calls injectSysConfig
      if (!DECORATE.shouldInjectSQLComment()) {
        return sql;
      }
      if (CallDepthThreadLocalMap.incrementCallDepth(Connection.class) > 0) {
        return null;
      }
      final String inputSql = sql;
      final DBInfo dbInfo =
          JDBCDecorator.parseDBInfo(
              connection, InstrumentationContext.get(Connection.class, DBInfo.class));
      String dbService = DECORATE.getDbService(dbInfo);
      if (dbService != null) {
        dbService =
            traceConfig(activeSpan()).getServiceMapping().getOrDefault(dbService, dbService);
      }

      boolean append =
          DECORATE.DBM_ALWAYS_APPEND_SQL_COMMENT || "sqlserver".equals(dbInfo.getType());
      sql =
          SQLCommenter.inject(
              sql, dbService, dbInfo.getType(), dbInfo.getHost(), dbInfo.getDb(), null, append);
      return inputSql;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addDBInfo(
        @Advice.This Connection connection,
        @Advice.Enter final String inputSql,
        @Advice.Return final PreparedStatement statement) {
      if (null == inputSql) {
        return;
      }
      ContextStore<Statement, DBQueryInfo> contextStore =
          InstrumentationContext.get(Statement.class, DBQueryInfo.class);
      if (null != statement && null == contextStore.get(statement)) {
        DBQueryInfo info = DBQueryInfo.ofPreparedStatement(inputSql);
        contextStore.put(statement, info);
        logQueryInfoInjection(connection, statement, info);
      }
      CallDepthThreadLocalMap.reset(Connection.class);
    }
  }
}
