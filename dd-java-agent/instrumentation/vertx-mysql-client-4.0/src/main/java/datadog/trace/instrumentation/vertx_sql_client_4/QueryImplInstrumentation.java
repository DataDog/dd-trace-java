package datadog.trace.instrumentation.vertx_sql_client_4;

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

@AutoService(Instrumenter.class)
public class QueryImplInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public QueryImplInstrumentation() {
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
  public String instrumentedType() {
    return "io.vertx.sqlclient.impl.SqlClientBase$QueryImpl";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("execute"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.vertx.core.Handler"))),
        packageName + ".QueryAdvice$Execute");
    transformation.applyAdvice(
        isMethod()
            .and(isVirtual())
            .and(named("copy"))
            .and(returns(named("io.vertx.sqlclient.impl.QueryBase"))),
        packageName + ".QueryAdvice$Copy");
  }
}
