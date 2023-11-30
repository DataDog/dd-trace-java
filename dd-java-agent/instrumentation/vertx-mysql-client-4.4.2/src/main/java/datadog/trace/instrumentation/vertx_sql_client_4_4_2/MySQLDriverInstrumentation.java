package datadog.trace.instrumentation.vertx_sql_client_4_4_2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.Map;

@AutoService(Instrumenter.class)
public class MySQLDriverInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public MySQLDriverInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.vertx.sqlclient.SqlClient", DBInfo.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.mysqlclient.spi.MySQLDriver";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isPrivate()
            .and(named("newPoolImpl"))
            .and(takesArguments(4).and(takesArgument(1, named("java.util.function.Supplier")))),
        packageName + ".MySQLDriverAdvice");
  }
}
