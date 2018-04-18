package stackstate.trace.instrumentation.jdbc;

import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import stackstate.trace.agent.tooling.DDAdvice;
import stackstate.trace.agent.tooling.DDTransformers;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.bootstrap.CallDepthThreadLocalMap;
import stackstate.trace.bootstrap.JDBCMaps;

@AutoService(Instrumenter.class)
public final class ConnectionInstrumentation extends Instrumenter.Configurable {

  public ConnectionInstrumentation() {
    super("jdbc");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(not(isInterface()).and(failSafe(isSubTypeOf(Connection.class))))
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    nameStartsWith("prepare")
                        .and(takesArgument(0, String.class))
                        .and(returns(PreparedStatement.class)),
                    ConnectionPrepareAdvice.class.getName()))
        .transform(
            DDAdvice.create().advice(isConstructor(), ConnectionConstructorAdvice.class.getName()))
        .asDecorator();
  }

  public static class ConnectionPrepareAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addDBInfo(
        @Advice.Argument(0) final String sql, @Advice.Return final PreparedStatement statement) {
      JDBCMaps.preparedStatements.put(statement, sql);
    }
  }

  public static class ConnectionConstructorAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static int constructorEnter() {
      // We use this to make sure we only apply the exit instrumentation
      // after the constructors are done calling their super constructors.
      return CallDepthThreadLocalMap.get(Connection.class).incrementCallDepth();
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void constructorExit(
        @Advice.Enter final int depth, @Advice.This final Connection connection)
        throws SQLException {
      if (depth == 0) {
        CallDepthThreadLocalMap.get(Connection.class).reset();
        final String url = connection.getMetaData().getURL();
        if (url != null) {
          // Remove end of url to prevent passwords from leaking:
          final String sanitizedURL = url.replaceAll("[?;].*", "");
          final String type = url.split(":")[1];
          String user = connection.getMetaData().getUserName();
          if (user != null && user.trim().equals("")) {
            user = null;
          }
          JDBCMaps.connectionInfo.put(connection, new JDBCMaps.DBInfo(sanitizedURL, type, user));
        }
      }
    }
  }
}
