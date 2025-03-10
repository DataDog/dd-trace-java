package datadog.trace.instrumentation.vertx_sql_client_39;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.HashMap;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class SqlClientBaseInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public SqlClientBaseInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("io.vertx.sqlclient.SqlClient", DBInfo.class.getName());
    contextStores.put("io.vertx.sqlclient.Query", "datadog.trace.api.Pair");
    return contextStores;
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.sqlclient.impl.SqlClientBase";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("query"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("java.lang.String"))),
        packageName + ".SqlClientBaseAdvice$NormalQuery");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("preparedQuery"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("java.lang.String"))),
        packageName + ".SqlClientBaseAdvice$PreparedQuery");
  }
}
