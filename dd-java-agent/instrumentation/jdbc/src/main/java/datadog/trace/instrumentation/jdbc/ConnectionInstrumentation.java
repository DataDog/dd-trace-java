package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.PREPARED_STATEMENTS_SQL;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.sql.PreparedStatement;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class ConnectionInstrumentation extends Instrumenter.Tracing {

  private static final String[] CONCRETE_TYPES = {
    // jt400
    "com.ibm.as400.access.AS400JDBCConnection",
    // possibly need more coverage
    "com.microsoft.sqlserver.jdbc.SQLServerConnection",
    // should cover mysql
    "com.mysql.jdbc.Connection",
    "com.mysql.jdbc.jdbc1.Connection",
    "com.mysql.jdbc.jdbc2.Connection",
    "com.mysql.jdbc.ConnectionImpl",
    "com.mysql.jdbc.JDBC4Connection",
    "com.mysql.cj.jdbc.JdbcConnection",
    // should cover derby
    "org.apache.derby.impl.jdbc.EmbedConnection",
    // complete
    "org.h2.jdbc.JdbcConnection",
    // complete
    "org.hsqldb.jdbc.JDBCConnection",
    "org.hsqldb.jdbc.jdbcConnection",
    "org.apache.hive.jdbc.HiveConnection",
    // complete
    "org.mariadb.jdbc.MariaDbConnection",
    "org.mariadb.jdbc.MySQLConnection",

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
    // sqlite seems to be complete
    "org.sqlite.Conn",
    "org.sqlite.jdbc3.JDBC3Connection",
    "org.sqlite.jdbc4.JDBC4Connection",
    // this won't match any class unless the property is set
    Config.get().getJdbcConnectionClassName()
  };

  private static final String[] ABSTRACT_TYPES = {
    // this should cover DB2
    "com.ibm.db2.jcc.DB2Connection",
    // this should cover Oracle
    "oracle.jdbc.OracleConnection",
    // this won't match any class unless the property is set
    Config.get().getJdbcConnectionClassName()
  };

  public ConnectionInstrumentation() {
    super("jdbc");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return PreparedStatementInstrumentation.CLASS_LOADER_MATCHER;
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.sql.PreparedStatement", UTF8BytesString.class.getName());
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
        nameStartsWith("prepare")
            .and(takesArgument(0, String.class))
            // Also include CallableStatement, which is a sub type of PreparedStatement
            .and(returns(hasInterface(named("java.sql.PreparedStatement")))),
        ConnectionInstrumentation.class.getName() + "$ConnectionPrepareAdvice");
  }

  public static class ConnectionPrepareAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addDBInfo(
        @Advice.Argument(0) final String sql, @Advice.Return final PreparedStatement statement) {
      ContextStore<PreparedStatement, UTF8BytesString> contextStore =
          InstrumentationContext.get(PreparedStatement.class, UTF8BytesString.class);
      if (null == contextStore.get(statement)) {
        // Sometimes the prepared statement is not reused, but the underlying String is reused, so
        // check if we have seen this String before
        UTF8BytesString utf8Sql = PREPARED_STATEMENTS_SQL.computeIfAbsent(sql, UTF8_ENCODE);
        contextStore.putIfAbsent(statement, utf8Sql);
      }
    }
  }
}
