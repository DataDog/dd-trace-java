package datadog.trace.instrumentation.vertx_sql_client_39;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class PreparedStatementImplInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
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
