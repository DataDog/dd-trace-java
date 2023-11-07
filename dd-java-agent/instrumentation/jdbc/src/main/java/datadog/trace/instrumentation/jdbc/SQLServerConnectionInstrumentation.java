package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.INJECT_COMMENT;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.logQueryInfoInjection;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
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

@AutoService(Instrumenter.class)
public class SQLServerConnectionInstrumentation extends AbstractConnectionInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.ForConfiguredType {

  /**
   * Instrumentation class for connections for SQL Server, which is a Database Monitoring supported
   * DB *
   */
  public SQLServerConnectionInstrumentation() {
    super("jdbc", "dbm");
  }

  // Classes to cover all currently supported
  // db types for the Database Monitoring product
  static final String[] CONCRETE_TYPES = {
    "com.microsoft.sqlserver.jdbc.SQLServerConnection",
    // jtds (for SQL Server and Sybase)
    "net.sourceforge.jtds.jdbc.ConnectionJDBC2", // 1.2
    "net.sourceforge.jtds.jdbc.JtdsConnection", // 1.3
  };

  // append mode will prepend the SQL comment to the raw sql query
  private static final boolean appendComment = true;

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
  public String configuredMatchingType() {
    // this won't match any class unless the property is set
    return InstrumenterConfig.get().getJdbcConnectionClassName();
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        nameStartsWith("prepare")
            .and(takesArgument(0, String.class))
            // Also include CallableStatement, which is a subtype of PreparedStatement
            .and(returns(hasInterface(named("java.sql.PreparedStatement")))),
        SQLServerConnectionInstrumentation.class.getName() + "$ConnectionAdvice");
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
      if (INJECT_COMMENT) {
        final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Connection.class);
        if (callDepth > 0) {
          return null;
        }
        final String inputSql = sql;
        final DBInfo dbInfo =
            JDBCDecorator.parseDBInfo(
                connection, InstrumentationContext.get(Connection.class, DBInfo.class));
        sql = SQLCommenter.inject(sql, DECORATE.getDbService(dbInfo), appendComment);
        return inputSql;
      }
      return sql;
    }

    public static void addDBInfo(
        Connection connection, final String inputSql, final PreparedStatement statement) {
      if (null == inputSql) {
        return;
      }
      ContextStore<Statement, DBQueryInfo> contextStore =
          InstrumentationContext.get(Statement.class, DBQueryInfo.class);
      if (null == contextStore.get(statement)) {
        DBQueryInfo info = DBQueryInfo.ofPreparedStatement(inputSql);
        contextStore.put(statement, info);
        logQueryInfoInjection(connection, statement, info);
      }
      CallDepthThreadLocalMap.reset(Connection.class);
    }
  }
}
