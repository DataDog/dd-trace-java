package datadog.trace.instrumentation.vertx_sql_client;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isVirtual;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class PreparedQueryInstrumentation extends Instrumenter.Tracing {
  public PreparedQueryInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("io.vertx.sqlclient.PreparedQuery");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "io.vertx.sqlclient.Query", "datadog.trace.instrumentation.vertx_sql_client.QueryInfo");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".QueryInfo",
      packageName + ".QueryResultHandlerWrapper",
      packageName + ".VertxSqlClientDecorator",
      packageName + ".QueryAdvice",
      packageName + ".QueryAdvice$Copy",
      packageName + ".QueryAdvice$Execute",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return implementsInterface(named("io.vertx.sqlclient.PreparedQuery"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod()
            .and(isPublic())
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(1, named("io.vertx.core.Handler"))),
        packageName + ".QueryAdvice$Execute");
    transformers.put(
        isMethod()
            .and(isPublic())
            .and(named("executeBatch"))
            .and(takesArguments(2))
            .and(takesArgument(1, named("io.vertx.core.Handler"))),
        packageName + ".QueryAdvice$Execute");
    transformers.put(
        isMethod()
            .and(isVirtual())
            .and(named("copy"))
            .and(returns(named("io.vertx.sqlclient.impl.QueryBase"))),
        packageName + ".QueryAdvice$Copy");
    return transformers;
  }
}
