package datadog.trace.instrumentation.postgresql;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;

public final class PgPreparedStatementInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.postgresql.jdbc.PgPreparedStatement";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(),
        "datadog.trace.instrumentation.postgresql.PgPreparedStatementAdvice$ConstructorAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("executeQuery")).and(takesArguments(0)),
        "datadog.trace.instrumentation.postgresql.PgPreparedStatementAdvice$ExecuteAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("executeUpdate")).and(takesArguments(0)),
        "datadog.trace.instrumentation.postgresql.PgPreparedStatementAdvice$ExecuteAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("execute")).and(takesArguments(0)),
        "datadog.trace.instrumentation.postgresql.PgPreparedStatementAdvice$ExecuteAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("executeBatch")).and(takesArguments(0)),
        "datadog.trace.instrumentation.postgresql.PgPreparedStatementAdvice$ExecuteAdvice");
  }
}
