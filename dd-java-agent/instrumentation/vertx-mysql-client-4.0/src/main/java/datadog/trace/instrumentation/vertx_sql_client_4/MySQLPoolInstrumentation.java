package datadog.trace.instrumentation.vertx_sql_client_4;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;

import java.util.Map;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public class MySQLPoolInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public MySQLPoolInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.vertx.sqlclient.SqlClient", DBInfo.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".VertxSqlClientDecorator",
    };
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.mysqlclient.MySQLPool";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isStatic()
            .and(named("pool"))
            .and(takesArguments(3)
                .and(takesArgument(1, named("io.vertx.mysqlclient.MySQLConnectOptions")))),
        packageName + ".MySQLPoolAdvice");
  }
}
