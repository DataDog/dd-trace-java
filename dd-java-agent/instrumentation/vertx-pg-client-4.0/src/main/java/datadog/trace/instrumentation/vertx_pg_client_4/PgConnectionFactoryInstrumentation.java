package datadog.trace.instrumentation.vertx_pg_client_4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class PgConnectionFactoryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public PgConnectionFactoryInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.vertx.pgclient.impl.PgConnectionFactory", DBInfo.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.pgclient.impl.PgConnectionFactory";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(2))
            .and(takesArgument(1, named("io.vertx.pgclient.PgConnectOptions"))),
        packageName + ".PgConnectionFactoryConstructorAdvice");
  }
}
