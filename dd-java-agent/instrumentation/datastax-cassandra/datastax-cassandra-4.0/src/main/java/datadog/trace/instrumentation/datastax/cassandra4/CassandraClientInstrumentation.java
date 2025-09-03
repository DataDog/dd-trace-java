package datadog.trace.instrumentation.datastax.cassandra4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class CassandraClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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
      packageName + ".CassandraClientDecorator",
      packageName + ".TracingSession",
      packageName + ".ContactPointsUtil",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("init"))
            .and(isStatic())
            .and(takesArguments(3))
            .and(takesArgument(1, named("java.util.Set")))
            .and(returns(named("java.util.concurrent.CompletionStage"))),
        packageName + ".CassandraClientAdvice");
  }
}
