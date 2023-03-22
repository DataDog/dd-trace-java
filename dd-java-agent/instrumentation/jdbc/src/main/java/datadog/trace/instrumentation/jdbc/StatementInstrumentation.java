package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DBM_TRACE_INJECTED;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DATABASE_QUERY;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.SQL_COMMENT_INJECTION_FULL;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.SQL_COMMENT_INJECTION_MODE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.SQL_COMMENT_INJECTION_STATIC;
import static datadog.trace.instrumentation.jdbc.SQLCommentInjectorAdaptor.SETTER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class StatementInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  public StatementInstrumentation() {
    super("jdbc");
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("java.sql.Statement"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.sql.Connection", DBInfo.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JDBCDecorator",
      packageName + ".SQLCommenter",
      packageName + ".SQLCommentInjectorAdaptor",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        nameStartsWith("execute").and(takesArgument(0, String.class)).and(isPublic()),
        StatementInstrumentation.class.getName() + "$StatementAdvice");
  }

  public static class StatementAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0, readOnly = false) String sql,
        @Advice.This final Statement statement) {
      // TODO consider matching known non-wrapper implementations to avoid this check
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Statement.class);
      if (callDepth > 0) {
        return null;
      }
      try {
        final Connection connection = statement.getConnection();
        final AgentSpan span = startSpan(DATABASE_QUERY);
        DECORATE.afterStart(span);
        DECORATE.onConnection(
            span, connection, InstrumentationContext.get(Connection.class, DBInfo.class));
        final String copy = sql;
        if (span != null && JDBCDecorator.injectSQLComment()) {
          if (SQL_COMMENT_INJECTION_MODE.equals(SQL_COMMENT_INJECTION_STATIC)) {
            SQLCommenter carrier =
                new SQLCommenter(SQL_COMMENT_INJECTION_STATIC, sql, span.getServiceName());
            carrier.inject();
            sql = carrier.getCommentedSQL();
          } else if (SQL_COMMENT_INJECTION_MODE.equals(SQL_COMMENT_INJECTION_FULL)) {
            SQLCommenter carrier =
                new SQLCommenter(
                    SQL_COMMENT_INJECTION_FULL,
                    sql,
                    span.getServiceName(),
                    span.getTraceId(),
                    span.getSpanId());
            // forces a sampling decision & sets the priority on the carrier
            propagate().inject(span, carrier, SETTER, TracePropagationStyle.SQL_COMMENT);
            carrier.inject();
            sql = carrier.getCommentedSQL();
            // set the dbm trace injected tag on the span
            span.setTag(DBM_TRACE_INJECTED, true);
          }
        }
        DECORATE.onStatement(span, DBQueryInfo.ofStatement(copy));
        return activateSpan(span);
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
      CallDepthThreadLocalMap.reset(Statement.class);
    }
  }
}
