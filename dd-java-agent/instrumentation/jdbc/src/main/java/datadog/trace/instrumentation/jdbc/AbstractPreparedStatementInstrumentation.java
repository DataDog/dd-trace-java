package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class AbstractPreparedStatementInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.HasMethodAdvice {

  public AbstractPreparedStatementInstrumentation(
      String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  /**
   * The JDBC setters whose second argument is the bound value, except {@code setNull} and {@code
   * setObject}, whose arguments vary.
   */
  private static final String[] VALUE_SETTERS = {
    "setArray",
    "setAsciiStream",
    "setBigDecimal",
    "setBinaryStream",
    "setBlob",
    "setBoolean",
    "setByte",
    "setBytes",
    "setCharacterStream",
    "setClob",
    "setDate",
    "setDouble",
    "setFloat",
    "setInt",
    "setLong",
    "setNCharacterStream",
    "setNClob",
    "setNString",
    "setRef",
    "setRowId",
    "setShort",
    "setSQLXML",
    "setString",
    "setTime",
    "setTimestamp",
    "setUnicodeStream",
    "setURL"
  };

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("java.sql.Statement", DBQueryInfo.class.getName());
    contextStore.put("java.sql.Connection", JDBCConnectionContext.class.getName());
    if (InstrumenterConfig.get().isJdbcCaptureQueryParameters()) {
      contextStore.put("java.sql.PreparedStatement", QueryParameters.class.getName());
    }
    return contextStore;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    ElementMatcher.Junction<MethodDescription> execute =
        nameStartsWith("execute").and(takesArguments(0)).and(isPublic());
    if (!InstrumenterConfig.get().isJdbcCaptureQueryParameters()) {
      transformer.applyAdvice(
          execute,
          AbstractPreparedStatementInstrumentation.class.getName() + "$PreparedStatementAdvice");
    } else {
      // values are not captured for batches, which run several sets of values
      transformer.applyAdvices(
          execute.and(nameEndsWith("Batch")),
          AbstractPreparedStatementInstrumentation.class.getName() + "$PreparedStatementAdvice",
          AbstractPreparedStatementInstrumentation.class.getName()
              + "$InvalidateQueryParametersAdvice");
      transformer.applyAdvice(
          execute.and(not(nameEndsWith("Batch"))),
          AbstractPreparedStatementInstrumentation.class.getName()
              + "$CaptureQueryParametersAdvice");
      // index for PreparedStatement, name for CallableStatement
      ElementMatcher.Junction<MethodDescription> parameterSetter =
          isPublic().and(takesArgument(0, int.class).or(takesArgument(0, String.class)));
      transformer.applyAdvice(
          parameterSetter.and(
              namedOneOf(VALUE_SETTERS)
                  .and(takesArguments(2).or(takesArguments(3)))
                  .or(named("setObject").and(takesArguments(2)))),
          AbstractPreparedStatementInstrumentation.class.getName() + "$SetParameterAdvice");
      transformer.applyAdvice(
          parameterSetter
              .and(named("setObject"))
              .and(takesArguments(3).or(takesArguments(4)))
              .and(takesArgument(2, int.class).or(takesArgument(2, named("java.sql.SQLType")))),
          AbstractPreparedStatementInstrumentation.class.getName() + "$SetObjectWithSqlTypeAdvice");
      transformer.applyAdvice(
          parameterSetter.and(named("setNull")).and(takesArgument(1, int.class)),
          AbstractPreparedStatementInstrumentation.class.getName() + "$SetNullAdvice");
      transformer.applyAdvice(
          isPublic().and(named("clearParameters")).and(takesArguments(0)),
          AbstractPreparedStatementInstrumentation.class.getName() + "$ClearParametersAdvice");
    }
  }

  public static class PreparedStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(@Advice.This final Statement statement) {
      int depth = CallDepthThreadLocalMap.incrementCallDepth(Statement.class);
      if (depth > 0) {
        return null;
      }
      final AgentSpan span =
          DECORATE.startPreparedStatementSpan(
              statement,
              InstrumentationContext.get(Statement.class, DBQueryInfo.class),
              InstrumentationContext.get(Connection.class, JDBCConnectionContext.class));
      return null == span ? null : activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final ContextScope scope, @Advice.Thrown final Throwable throwable) {
      CallDepthThreadLocalMap.decrementCallDepth(Statement.class);
      if (scope == null) {
        return;
      }
      AgentSpan span = spanFromScope(scope);
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }

  /** {@link PreparedStatementAdvice} that also puts the bound values on the span. */
  public static class CaptureQueryParametersAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(@Advice.This final Statement statement) {
      int depth = CallDepthThreadLocalMap.incrementCallDepth(Statement.class);
      if (depth > 0) {
        return null;
      }
      final AgentSpan span =
          DECORATE.startPreparedStatementSpan(
              statement,
              InstrumentationContext.get(Statement.class, DBQueryInfo.class),
              InstrumentationContext.get(Connection.class, JDBCConnectionContext.class));
      if (null == span) {
        return null;
      }
      if (statement instanceof PreparedStatement) {
        final QueryParameters queryParameters =
            InstrumentationContext.get(PreparedStatement.class, QueryParameters.class)
                .get((PreparedStatement) statement);
        if (null != queryParameters) {
          queryParameters.applyTo(span);
        }
      }
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final ContextScope scope, @Advice.Thrown final Throwable throwable) {
      CallDepthThreadLocalMap.decrementCallDepth(Statement.class);
      if (scope == null) {
        return;
      }
      AgentSpan span = spanFromScope(scope);
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }

  /**
   * Records the values on exit, once the driver accepted them. Only the outermost setter records,
   * since drivers may implement a setter with another one, for example a named setter with the
   * positional one.
   */
  public static class SetParameterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static int onEnter() {
      return CallDepthThreadLocalMap.incrementCallDepth(QueryParameters.class);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final int depth,
        @Advice.This final PreparedStatement statement,
        @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) final Object key,
        @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) final Object value,
        @Advice.Origin("#m") final String setter,
        @Advice.Thrown final Throwable throwable) {
      CallDepthThreadLocalMap.decrementCallDepth(QueryParameters.class);
      if (depth > 0 || null != throwable) {
        return;
      }
      QueryParameters.getOrCreate(
              InstrumentationContext.get(PreparedStatement.class, QueryParameters.class), statement)
          .bind(key, setter, value);
    }
  }

  public static class SetObjectWithSqlTypeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static int onEnter() {
      return CallDepthThreadLocalMap.incrementCallDepth(QueryParameters.class);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final int depth,
        @Advice.This final PreparedStatement statement,
        @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) final Object key,
        @Advice.Argument(1) final Object value,
        @Advice.Argument(value = 2, typing = Assigner.Typing.DYNAMIC) final Object sqlType,
        @Advice.Thrown final Throwable throwable) {
      CallDepthThreadLocalMap.decrementCallDepth(QueryParameters.class);
      if (depth > 0 || null != throwable) {
        return;
      }
      QueryParameters.getOrCreate(
              InstrumentationContext.get(PreparedStatement.class, QueryParameters.class), statement)
          .bindWithSqlType(key, value, sqlType);
    }
  }

  public static class SetNullAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static int onEnter() {
      return CallDepthThreadLocalMap.incrementCallDepth(QueryParameters.class);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final int depth,
        @Advice.This final PreparedStatement statement,
        @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) final Object key,
        @Advice.Argument(1) final int sqlType,
        @Advice.Thrown final Throwable throwable) {
      CallDepthThreadLocalMap.decrementCallDepth(QueryParameters.class);
      if (depth > 0 || null != throwable) {
        return;
      }
      QueryParameters.getOrCreate(
              InstrumentationContext.get(PreparedStatement.class, QueryParameters.class), statement)
          .bindNull(key, sqlType);
    }
  }

  /** Drivers may replace the bound values while running a batch, without calling a setter. */
  public static class InvalidateQueryParametersAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.This final Statement statement) {
      if (statement instanceof PreparedStatement) {
        final QueryParameters queryParameters =
            InstrumentationContext.get(PreparedStatement.class, QueryParameters.class)
                .get((PreparedStatement) statement);
        if (null != queryParameters) {
          queryParameters.invalidate();
        }
      }
    }
  }

  public static class ClearParametersAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This final PreparedStatement statement) {
      final QueryParameters queryParameters =
          InstrumentationContext.get(PreparedStatement.class, QueryParameters.class).get(statement);
      if (null != queryParameters) {
        queryParameters.clear();
      }
    }
  }
}
