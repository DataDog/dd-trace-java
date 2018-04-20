package stackstate.trace.instrumentation.jdbc;

import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.noop.NoopScopeManager;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.agent.tooling.STSAdvice;
import stackstate.trace.agent.tooling.STSTransformers;
import stackstate.trace.api.STSTags;
import stackstate.trace.bootstrap.JDBCMaps;

@AutoService(Instrumenter.class)
public final class StatementInstrumentation extends Instrumenter.Configurable {

  public StatementInstrumentation() {
    super("jdbc");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(not(isInterface()).and(failSafe(isSubTypeOf(Statement.class))))
        .transform(STSTransformers.defaultTransformers())
        .transform(
            STSAdvice.create()
                .advice(
                    nameStartsWith("execute").and(takesArgument(0, String.class)).and(isPublic()),
                    StatementAdvice.class.getName()))
        .asDecorator();
  }

  public static class StatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(
        @Advice.Argument(0) final String sql, @Advice.This final Statement statement) {
      final Connection connection;
      try {
        connection = statement.getConnection();
      } catch (final Throwable e) {
        // Had some problem getting the connection.
        return NoopScopeManager.NoopScope.INSTANCE;
      }

      JDBCMaps.DBInfo dbInfo = JDBCMaps.connectionInfo.get(connection);
      if (dbInfo == null) {
        dbInfo = JDBCMaps.DBInfo.UNKNOWN;
      }

      final Scope scope =
          GlobalTracer.get().buildSpan(dbInfo.getType() + ".query").startActive(true);

      final Span span = scope.span();

      Tags.DB_TYPE.set(span, dbInfo.getType());
      Tags.SPAN_KIND.set(span, Tags.SPAN_KIND_CLIENT);
      Tags.COMPONENT.set(span, "java-jdbc-statement");

      span.setTag(STSTags.SERVICE_NAME, dbInfo.getType());
      span.setTag(STSTags.RESOURCE_NAME, sql);
      span.setTag(STSTags.SPAN_TYPE, "sql");
      span.setTag("span.origin.type", statement.getClass().getName());
      span.setTag("db.jdbc.url", dbInfo.getUrl());

      if (dbInfo.getUser() != null) {
        Tags.DB_USER.set(span, dbInfo.getUser());
      }
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final Scope scope, @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        final Span span = scope.span();
        Tags.ERROR.set(span, true);
        span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
      }
      scope.close();
    }
  }
}
