package datadog.trace.instrumentation.vertx_sql_client_4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.Map;

@AutoService(Instrumenter.class)
public class MySQLPoolImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public MySQLPoolImplInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.vertx.sqlclient.SqlClient", DBInfo.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.mysqlclient.impl.MySQLPoolImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isStatic()
            .and(isPublic())
            .and(isMethod())
            .and(named("create"))
            .and(takesArguments(4))
            .and(takesArgument(2, named("io.vertx.mysqlclient.MySQLConnectOptions"))),
        packageName + ".MySQLPoolImplAdvice");
  }
}
