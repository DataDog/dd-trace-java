package datadog.trace.instrumentation.vertx_sql_client_4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.HashMap;
import java.util.Map;

@AutoService(Instrumenter.class)
public class MySQLConnectionImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public MySQLConnectionImplInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("io.vertx.mysqlclient.impl.MySQLConnectionFactory", DBInfo.class.getName());
    contextStores.put("io.vertx.sqlclient.SqlClient", DBInfo.class.getName());
    return contextStores;
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.mysqlclient.impl.MySQLConnectionImpl";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor()
            .and(takesArgument(0, named("io.vertx.mysqlclient.impl.MySQLConnectionFactory"))),
        packageName + ".MySQLConnectionImplConstructorAdvice");
  }
}
