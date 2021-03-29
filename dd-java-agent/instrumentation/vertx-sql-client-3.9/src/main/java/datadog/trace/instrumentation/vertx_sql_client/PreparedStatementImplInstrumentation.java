package datadog.trace.instrumentation.vertx_sql_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class PreparedStatementImplInstrumentation extends Instrumenter.Tracing {
  public PreparedStatementImplInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStores = new HashMap<>();
    contextStores.put(
        "io.vertx.sqlclient.PreparedStatement",
        "datadog.trace.instrumentation.vertx_sql_client.QueryInfo");
    contextStores.put(
        "io.vertx.sqlclient.Query", "datadog.trace.instrumentation.vertx_sql_client.QueryInfo");
    return contextStores;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".QueryInfo", packageName + ".PreparedStatementQueryAdvice",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("io.vertx.sqlclient.impl.PreparedStatementImpl");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod().and(isPublic()).and(named("query")).and(takesNoArguments()),
        packageName + ".PreparedStatementQueryAdvice");
    return transformers;
  }
}
