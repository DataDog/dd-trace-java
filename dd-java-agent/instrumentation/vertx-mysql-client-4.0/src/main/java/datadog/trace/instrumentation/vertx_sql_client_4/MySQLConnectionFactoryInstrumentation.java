package datadog.trace.instrumentation.vertx_sql_client_4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.Map;

@AutoService(Instrumenter.class)
public class MySQLConnectionFactoryInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public MySQLConnectionFactoryInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.vertx.mysqlclient.impl.MySQLConnectionFactory", DBInfo.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.mysqlclient.impl.MySQLConnectionFactory";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor()
            .and(takesArguments(2))
            .and(takesArgument(1, named("io.vertx.mysqlclient.MySQLConnectOptions"))),
        packageName + ".MySQLConnectionFactoryConstructorAdvice");
  }
}
