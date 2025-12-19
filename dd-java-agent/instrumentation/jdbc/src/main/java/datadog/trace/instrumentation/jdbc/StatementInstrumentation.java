package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DBM_TRACE_INJECTED;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DATABASE_QUERY;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.INJECT_COMMENT;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.W3CTraceParent;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class StatementInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {

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
      "datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        nameStartsWith("execute").and(takesArgument(0, String.class)).and(isPublic()),
        StatementInstrumentation.class.getName() + "$StatementAdvice");
  }

  public static class StatementAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0, readOnly = false) String sql,
        @Advice.AllArguments() Object[] args,
        @Advice.This final Statement statement) {
      // TODO consider matching known non-wrapper implementations to avoid this check
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Statement.class);
      if (callDepth > 0) {
        return null;
      }
      try {
        final Connection connection = statement.getConnection();
        final DBInfo dbInfo =
            JDBCDecorator.parseDBInfo(
                connection, InstrumentationContext.get(Connection.class, DBInfo.class));
        boolean injectTraceContext = DECORATE.shouldInjectTraceContext(dbInfo);
        final AgentSpan span;
        final boolean isSqlServer = DECORATE.isSqlServer(dbInfo);
        final boolean isOracle = DECORATE.isOracle(dbInfo);

        if (INJECT_COMMENT && injectTraceContext) {
          if (isSqlServer) {
            // The span ID is pre-determined so that we can reference it when setting the context
            final long spanID = DECORATE.setContextInfo(connection, dbInfo);
            // we then force that pre-determined span ID for the span covering the actual query
            span = AgentTracer.get().singleSpanBuilder(DATABASE_QUERY).withSpanId(spanID).start();
          } else if (isOracle) {
            span = startSpan(DATABASE_QUERY);
            DECORATE.setAction(span, connection);
          } else {
            span = startSpan(DATABASE_QUERY);
          }
        } else {
          span = startSpan(DATABASE_QUERY);
        }

        DECORATE.afterStart(span);
        DECORATE.onConnection(span, dbInfo);
        final String copy = sql;
        if (span != null && INJECT_COMMENT) {
          String traceParent = null;

          if (injectTraceContext) {
            Integer priority = span.forceSamplingDecision();
            if (priority != null) {
              if (!isSqlServer) {
                traceParent = W3CTraceParent.build(span.getTraceId(), span.getSpanId(), priority);
              }
              // set the dbm trace injected tag on the span
              span.setTag(DBM_TRACE_INJECTED, true);
            }
          }
          // For SQL Server and Oracle, trace context is propagated via
          // context_info and v$session.action respectively.
          // we should not also inject it into SQL comments to avoid duplication
          final boolean injectTraceInComment = injectTraceContext && !isSqlServer && !isOracle;

          // prepend mode will prepend the SQL comment to the raw sql query
          boolean appendComment = DECORATE.DBM_ALWAYS_APPEND_SQL_COMMENT;

          // There is a bug in the SQL Server JDBC driver that prevents
          // the generated keys from being returned when the
          // SQL comment is prepended to the SQL query.
          // We only append in this case to avoid the comment from being truncated.
          // @see https://github.com/microsoft/mssql-jdbc/issues/2729
          if (isSqlServer
              && !appendComment
              && args.length == 2
              && args[1] instanceof Integer
              && (Integer) args[1] == Statement.RETURN_GENERATED_KEYS) {
            appendComment = true;
          }

          sql =
              SQLCommenter.inject(
                  sql,
                  span.getServiceName(),
                  dbInfo.getType(),
                  dbInfo.getHost(),
                  dbInfo.getDb(),
                  injectTraceInComment ? traceParent : null,
                  appendComment);
        }
        DECORATE.onStatement(span, copy);
        return activateSpan(span);
      } catch (SQLException e) {
        // if we can't get the connection for any reason
        return null;
      } catch (BlockingException e) {
        CallDepthThreadLocalMap.reset(Statement.class);
        // re-throw blocking exceptions
        throw e;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      CallDepthThreadLocalMap.decrementCallDepth(Statement.class);
      if (scope == null) {
        return;
      }
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }
}
