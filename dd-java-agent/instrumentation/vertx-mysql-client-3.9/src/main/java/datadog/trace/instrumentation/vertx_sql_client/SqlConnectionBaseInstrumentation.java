package datadog.trace.instrumentation.vertx_sql_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class SqlConnectionBaseInstrumentation extends Instrumenter.Tracing {
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
    return new String[] {
      packageName + ".PrepareHandlerWrapper", packageName + ".SqlConnectionBasePrepareAdvice",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("io.vertx.sqlclient.impl.SqlConnectionBase");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(isPublic())
            .and(named("prepare"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("java.lang.String")))
            .and(takesArgument(1, named("io.vertx.core.Handler"))),
        packageName + ".SqlConnectionBasePrepareAdvice");
  }
}
