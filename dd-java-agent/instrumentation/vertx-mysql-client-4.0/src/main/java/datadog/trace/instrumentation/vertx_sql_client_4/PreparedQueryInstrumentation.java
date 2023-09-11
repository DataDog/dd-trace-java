package datadog.trace.instrumentation.vertx_sql_client_4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
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
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class PreparedQueryInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public PreparedQueryInstrumentation() {
    super("vertx", "vertx-sql-client");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.vertx.sqlclient.Query", "datadog.trace.api.Pair");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".QueryResultHandlerWrapper", packageName + ".VertxSqlClientDecorator",
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.vertx.sqlclient.PreparedQuery";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(1, named("io.vertx.core.Handler"))),
        packageName + ".QueryAdvice$Execute");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("executeBatch"))
            .and(takesArguments(2))
            .and(takesArgument(1, named("io.vertx.core.Handler"))),
        packageName + ".QueryAdvice$Execute");
    transformation.applyAdvice(
        isMethod()
            .and(isVirtual())
            .and(named("copy"))
            .and(returns(named("io.vertx.sqlclient.impl.QueryBase"))),
        packageName + ".QueryAdvice$Copy");
  }
}
