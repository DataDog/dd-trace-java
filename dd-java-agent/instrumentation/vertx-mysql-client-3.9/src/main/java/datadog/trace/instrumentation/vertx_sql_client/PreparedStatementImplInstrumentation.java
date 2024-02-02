package datadog.trace.instrumentation.vertx_sql_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;

@AutoService(Instrumenter.class)
public class PreparedStatementImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public PreparedStatementImplInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("io.vertx.sqlclient.PreparedStatement", "datadog.trace.api.Pair");
    contextStores.put("io.vertx.sqlclient.Query", "datadog.trace.api.Pair");
    return contextStores;
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.sqlclient.impl.PreparedStatementImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("query")).and(takesNoArguments()),
        packageName + ".PreparedStatementQueryAdvice");
  }
}
