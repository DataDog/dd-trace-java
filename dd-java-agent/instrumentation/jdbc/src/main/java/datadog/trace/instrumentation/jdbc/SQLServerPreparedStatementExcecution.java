package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DATABASE_QUERY;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class SQLServerPreparedStatementExcecution extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  public SQLServerPreparedStatementExcecution() {
    super("jdbc");
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("java.sql.PreparedStatement"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.sql.Connection", DBInfo.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        nameStartsWith("execute").and(takesArguments(0)).and(isPublic()),
        SQLServerPreparedStatementExcecution.class.getName()
            + "$SQLServerPreparedStatementExcecutionAdvice");
  }

  public static class SQLServerPreparedStatementExcecutionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final PreparedStatement statement) {
      try {
        final Connection connection = statement.getConnection();
        final DBInfo dbInfo =
            JDBCDecorator.parseDBInfo(
                connection, InstrumentationContext.get(Connection.class, DBInfo.class));

        // TODO: factor out this code
        if (!dbInfo.getType().equals("sqlserver")) {
          return null;
        }

          // TODO: remove
          System.out.println("HERE prepared " + dbInfo.getType());

          final AgentSpan span = startSpan(DATABASE_QUERY);
          Statement instrumentationStatement = connection.createStatement();
          instrumentationStatement.execute(
              "set context_info 0x"
                  + span.getTraceId().toHexString()
                  + DDSpanId.toHexStringPadded(span.getSpanId())
                  + "0");
          instrumentationStatement.close();
          return activateSpan(span);

        //return null;
      } catch (SQLException e) {
        // if we can't get the connection for any reason
        return null;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
      CallDepthThreadLocalMap.reset(PreparedStatement.class);
    }
  }
}
