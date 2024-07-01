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
import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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
      packageName + ".InstrumentationLogger",
    };
  }

  // prepend mode will prepend the SQL comment to the raw sql query
  private static final boolean appendComment = false;

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        nameStartsWith("execute").and(takesArgument(0, String.class)).and(isPublic()),
        StatementInstrumentation.class.getName() + "$StatementAdvice");
  }

  public static class StatementAdvice {
    @Advice.OnMethodEnter()
    public static AgentScope onEnter(
        @Advice.Argument(value = 0, readOnly = false) String sql,
        @Advice.This final Statement statement) {
      // TODO consider matching known non-wrapper implementations to avoid this check
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Statement.class);
      if (callDepth > 0) {
        return null;
      }
      try {
        System.out.println("StatementAdvice.onEnter");
        final Connection connection = statement.getConnection();
        final AgentSpan span = startSpan(DATABASE_QUERY);
        DECORATE.afterStart(span);
        final DBInfo dbInfo =
            JDBCDecorator.parseDBInfo(
                connection, InstrumentationContext.get(Connection.class, DBInfo.class));
        DECORATE.onConnection(span, dbInfo);
        final String copy = sql;
        System.out.println("HERE before if");
        Integer priority = null;
        if (span != null && INJECT_COMMENT) {
          System.out.println("HERE after if");
          String traceParent = null;

          boolean injectTraceContext = DECORATE.shouldInjectTraceContext(dbInfo);
          if (injectTraceContext) {
            priority = span.forceSamplingDecision();
            if (priority != null) {
              traceParent = DECORATE.traceParent(span, priority);
              // set the dbm trace injected tag on the span
              span.setTag(DBM_TRACE_INJECTED, true);
            }
          }
          sql =
              SQLCommenter.inject(
                  sql,
                  span.getServiceName(),
                  dbInfo.getType(),
                  dbInfo.getHost(),
                  dbInfo.getDb(),
                  traceParent,
                  injectTraceContext,
                  appendComment);
        }
        DECORATE.onStatement(span, copy);

        // TODO: factor out this code
        if (dbInfo.getType().equals("sqlserver")) {
          String forceSamplingDecision = "0";
          if ( priority != null && priority > 0 ){
            forceSamplingDecision = "1";
          }
          Statement instrumentationStatement = connection.createStatement();
          instrumentationStatement.execute(
              "set context_info 0x"
                      + forceSamplingDecision
                      + DDSpanId.toHexStringPadded(span.getSpanId())
                      + span.getTraceId().toHexString()
          );
          instrumentationStatement.close();
        }
        return activateSpan(span);
      } catch (SQLException e) {
        // if we can't get the connection for any reason
        System.out.println("HERE EXCEPTION " + e.getMessage());
        return null;
      } catch (BlockingException e) {
        CallDepthThreadLocalMap.reset(Statement.class);
        // re-throw blocking exceptions
        throw e;
      } catch (Throwable e) {
        // suppress anything else
        InstrumentationLogger.debug(
            "datadog.trace.instrumentation.jdbc.StatementInstrumentation", statement.getClass(), e);
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
