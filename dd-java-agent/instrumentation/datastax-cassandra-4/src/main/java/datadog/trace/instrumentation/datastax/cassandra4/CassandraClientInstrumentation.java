package datadog.trace.instrumentation.datastax.cassandra4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class CassandraClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public CassandraClientInstrumentation() {
    super("cassandra");
  }

  @Override
  public String instrumentedType() {
    return "com.datastax.oss.driver.internal.core.session.DefaultSession";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CassandraClientDecorator", packageName + ".TracingSession"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("init"))
            .and(isStatic())
            .and(takesArguments(3))
            .and(returns(named("java.util.concurrent.CompletionStage"))),
        packageName + ".CassandraClientAdvice");
  }
}
