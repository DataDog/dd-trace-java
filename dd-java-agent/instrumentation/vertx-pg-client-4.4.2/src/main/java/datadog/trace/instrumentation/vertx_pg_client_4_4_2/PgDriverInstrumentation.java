package datadog.trace.instrumentation.vertx_pg_client_4_4_2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class PgDriverInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public PgDriverInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.vertx.sqlclient.SqlClient", DBInfo.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.pgclient.spi.PgDriver";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPrivate()
            .and(named("newPoolImpl"))
            .and(takesArguments(4).and(takesArgument(1, named("java.util.function.Supplier")))),
        packageName + ".PgDriverAdvice");
  }
}
