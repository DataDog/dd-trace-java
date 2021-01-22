package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DATABASE_QUERY;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class PreparedStatementInstrumentation extends Instrumenter.Tracing {

  private static final String[] CONCRETE_TYPES = {
    // jt400
    "com.ibm.as400.access.AS400JDBCPreparedStatement",
    // probably patchy cover
    "com.microsoft.sqlserver.jdbc.SQLServerPreparedStatement",
    "com.microsoft.sqlserver.jdbc.SQLServerCallableStatement",
    // should cover mysql
    "com.mysql.cj.JdbcPreparedStatement",
    "com.mysql.jdbc.PreparedStatement",
    "com.mysql.jdbc.jdbc1.PreparedStatement",
    "com.mysql.jdbc.jdbc2.PreparedStatement",
    "com.mysql.jdbc.ServerPreparedStatement",
    "com.mysql.cj.jdbc.PreparedStatement",
    "com.mysql.cj.jdbc.ServerPreparedStatement",
    "com.mysql.cj.JdbcCallableStatement",
    "com.mysql.jdbc.CallableStatement",
    "com.mysql.jdbc.jdbc1.CallableStatement",
    "com.mysql.jdbc.jdbc2.CallableStatement",
    "com.mysql.cj.jdbc.CallableStatement",
    // covers hsqldb
    "org.hsqldb.jdbc.JDBCPreparedStatement",
    "org.hsqldb.jdbc.jdbcPreparedStatement",
    "org.hsqldb.jdbc.JDBCCallableStatement",
    "org.hsqldb.jdbc.jdbcCallableStatement",
    // should cover derby
    "org.apache.derby.impl.jdbc.EmbedPreparedStatement",
    "org.apache.derby.impl.jdbc.EmbedCallableStatement",
    // hive
    "org.apache.hive.jdbc.HivePreparedStatement",
    "org.apache.hive.jdbc.HiveCallableStatement",
    // covers h2
    "org.h2.jdbc.JdbcPreparedStatement",
    "org.h2.jdbc.JdbcCallableStatement",
    // covers mariadb
    "org.mariadb.jdbc.JdbcPreparedStatement",
    "org.mariadb.jdbc.JdbcCallableStatement",
    "org.mariadb.jdbc.ServerSidePreparedStatement",
    "org.mariadb.jdbc.ClientSidePreparedStatement",
    "org.mariadb.jdbc.MariaDbServerPreparedStatement",
    "org.mariadb.jdbc.MariaDbClientPreparedStatement",
    "org.mariadb.jdbc.MySQLPreparedStatement",
    "org.mariadb.jdbc.MySQLCallableStatement",
    "org.mariadb.jdbc.MySQLServerSidePreparedStatement",
    // should completely cover postgresql
    "org.postgresql.jdbc1.PreparedStatement",
    "org.postgresql.jdbc1.CallableStatement",
    "org.postgresql.jdbc1.Jdbc1PreparedStatement",
    "org.postgresql.jdbc1.Jdbc1CallableStatement",
    "org.postgresql.jdbc2.PreparedStatement",
    "org.postgresql.jdbc2.CallableStatement",
    "org.postgresql.jdbc2.Jdbc2PreparedStatement",
    "org.postgresql.jdbc2.Jdbc2CallableStatement",
    "org.postgresql.jdbc3.Jdbc3PreparedStatement",
    "org.postgresql.jdbc3.Jdbc3CallableStatement",
    "org.postgresql.jdbc3g.Jdbc3gPreparedStatement",
    "org.postgresql.jdbc3g.Jdbc3gCallableStatement",
    "org.postgresql.jdbc4.Jdbc4PreparedStatement",
    "org.postgresql.jdbc4.Jdbc4CallableStatement",
    "org.postgresql.jdbc.PgPreparedStatement",
    "org.postgresql.jdbc.PgCallableStatement",
    "postgresql.PreparedStatement",
    "postgresql.CallableStatement",
    // should completely cover sqlite
    "org.sqlite.jdbc3.JDBC3PreparedStatement",
    "org.sqlite.jdbc4.JDBC4PreparedStatement",
    "org.sqlite.PrepStmt",
    "test.TestPreparedStatement",
    // this won't match any classes unless set
    Config.get().getJdbcPreparedStatementClassName()
  };

  private static final String[] ABSTRACT_TYPES = {
    // should cover DB2
    "com.ibm.db2.jcc.DB2PreparedStatement",
    // should cover Oracle
    "oracle.jdbc.OraclePreparedStatement",
    // this won't match any classes unless set
    Config.get().getJdbcPreparedStatementClassName()
  };

  public PreparedStatementInstrumentation() {
    super("jdbc");
  }

  static ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassesNamed("java.sql.PreparedStatement");

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("java.sql.PreparedStatement", DBQueryInfo.class.getName());
    contextStore.put("java.sql.Statement", Boolean.class.getName());
    contextStore.put("java.sql.Connection", DBInfo.class.getName());
    return contextStore;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return namedOneOf(CONCRETE_TYPES)
        .or(safeHasSuperType(NameMatchers.<TypeDescription>namedOneOf(ABSTRACT_TYPES)));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JDBCDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        nameStartsWith("execute").and(takesArguments(0)).and(isPublic()),
        PreparedStatementInstrumentation.class.getName() + "$PreparedStatementAdvice");
  }

  public static class PreparedStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final PreparedStatement statement) {
      ContextStore<Statement, Boolean> interceptionTracker =
          InstrumentationContext.get(Statement.class, Boolean.class);
      if (Boolean.TRUE.equals(interceptionTracker.get(statement))) {
        return null;
      }
      interceptionTracker.put(statement, Boolean.TRUE);
      try {
        Connection connection = statement.getConnection();
        DBQueryInfo queryInfo =
            InstrumentationContext.get(PreparedStatement.class, DBQueryInfo.class).get(statement);
        if (null == queryInfo) {
          return null;
        }

        final AgentSpan span = startSpan(DATABASE_QUERY);
        DECORATE.afterStart(span);
        DECORATE.onConnection(
            span, connection, InstrumentationContext.get(Connection.class, DBInfo.class));
        DECORATE.onPreparedStatement(span, queryInfo);
        return activateSpan(span);
      } catch (SQLException e) {
        // if we can't get the connection for any reason
        return null;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This PreparedStatement statement,
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
      InstrumentationContext.get(Statement.class, Boolean.class).put(statement, null);
    }
  }
}
