package datadog.trace.instrumentation.vertx_pg_client_4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.HashMap;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class PgConnectionImplInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public PgConnectionImplInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("io.vertx.pgclient.impl.PgConnectionFactory", DBInfo.class.getName());
    contextStores.put("io.vertx.sqlclient.SqlClient", DBInfo.class.getName());
    return contextStores;
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.pgclient.impl.PgConnectionImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(0, named("io.vertx.pgclient.impl.PgConnectionFactory"))),
        packageName + ".PgConnectionImplConstructorAdvice");
  }
}
