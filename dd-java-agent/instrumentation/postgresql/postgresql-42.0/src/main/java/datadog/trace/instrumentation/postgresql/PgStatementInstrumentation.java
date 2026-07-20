package datadog.trace.instrumentation.postgresql;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;

public final class PgStatementInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.postgresql.jdbc.PgStatement";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("executeQuery"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        "datadog.trace.instrumentation.postgresql.PgStatementAdvice$ExecuteQueryAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("executeUpdate"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        "datadog.trace.instrumentation.postgresql.PgStatementAdvice$ExecuteQueryAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("execute"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        "datadog.trace.instrumentation.postgresql.PgStatementAdvice$ExecuteQueryAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("addBatch"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        "datadog.trace.instrumentation.postgresql.PgStatementAdvice$AddBatchAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("executeBatch")).and(takesArguments(0)),
        "datadog.trace.instrumentation.postgresql.PgStatementAdvice$ExecuteBatchAdvice");
  }
}
