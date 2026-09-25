package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.INJECT_COMMENT;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionMetadata;
import io.r2dbc.spi.Statement;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class ConnectionInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "io.r2dbc.spi.Connection";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("io.r2dbc.spi.Connection"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("createStatement"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        ConnectionInstrumentation.class.getName() + "$CreateStatementAdvice");
  }

  public static class CreateStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static R2dbcConnectionInfo onEnter(
        @Advice.This final Connection connection,
        @Advice.Argument(value = 0, readOnly = false) String sql) {
      final String originalSql = sql;
      String dbType = null;
      try {
        ConnectionMetadata metadata = connection.getMetadata();
        if (metadata != null) {
          String productName = metadata.getDatabaseProductName();
          if (productName != null) {
            dbType = productName.toLowerCase();
          }
        }
      } catch (Throwable ignored) {
        // Connection may be closed or metadata unavailable
      }
      if (INJECT_COMMENT) {
        sql = R2dbcSQLCommenter.inject(sql, null, dbType, null, null, null);
      }
      return R2dbcConnectionInfo.of(originalSql, dbType, null, null, null);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final R2dbcConnectionInfo info, @Advice.Return final Statement statement) {
      if (statement != null && info != null) {
        InstrumentationContext.get(Statement.class, R2dbcConnectionInfo.class).put(statement, info);
      }
    }
  }
}
