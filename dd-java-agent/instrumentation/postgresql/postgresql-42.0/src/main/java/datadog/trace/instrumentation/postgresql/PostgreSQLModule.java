package datadog.trace.instrumentation.postgresql;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class PostgreSQLModule extends InstrumenterModule.Tracing {

  public PostgreSQLModule() {
    super("postgresql");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PostgreSQLDecorator",
      packageName + ".PostgreSQLSQLCommenter",
      packageName + ".PgStatementAdvice",
      packageName + ".PgStatementAdvice$ExecuteQueryAdvice",
      packageName + ".PgStatementAdvice$AddBatchAdvice",
      packageName + ".PgStatementAdvice$ExecuteBatchAdvice",
      packageName + ".PgPreparedStatementAdvice",
      packageName + ".PgPreparedStatementAdvice$ConstructorAdvice",
      packageName + ".PgPreparedStatementAdvice$ExecuteAdvice",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "java.sql.Statement", "datadog.trace.bootstrap.instrumentation.jdbc.DBInfo");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new PgStatementInstrumentation(), new PgPreparedStatementInstrumentation());
  }
}
