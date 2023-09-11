package datadog.trace.instrumentation.vertx_sql_client_4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.HashMap;
import java.util.Map;

@AutoService(Instrumenter.class)
public class SqlConnectionBaseInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public SqlConnectionBaseInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put("io.vertx.sqlclient.SqlClient", DBInfo.class.getName());
    contextStores.put("io.vertx.sqlclient.PreparedStatement", "datadog.trace.api.Pair");
    return contextStores;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".PrepareHandlerWrapper"};
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.sqlclient.impl.SqlConnectionBase";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("prepare"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("java.lang.String")))
            .and(takesArgument(1, named("io.vertx.core.Handler"))),
        packageName + ".SqlConnectionBasePrepareAdvice");
  }
}
