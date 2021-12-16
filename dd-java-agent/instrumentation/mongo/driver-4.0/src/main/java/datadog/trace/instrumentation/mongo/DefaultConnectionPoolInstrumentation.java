package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class DefaultConnectionPoolInstrumentation extends Instrumenter.Tracing {
  public DefaultConnectionPoolInstrumentation() {
    super("mongo", "mongo-reactivestreams");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.mongodb.internal.connection.DefaultConnectionPool");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".CallbackWrapper"};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("getAsync"))
            .and(takesArgument(0, named("com.mongodb.internal.async.SingleResultCallback"))),
        packageName + ".Arg0Advice");
  }
}
