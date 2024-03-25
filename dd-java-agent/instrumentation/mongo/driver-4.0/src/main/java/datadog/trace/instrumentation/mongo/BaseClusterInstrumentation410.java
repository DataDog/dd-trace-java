package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class BaseClusterInstrumentation410 extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {
  public BaseClusterInstrumentation410() {
    super("mongo", "mongo-reactivestreams");
  }

  @Override
  public String instrumentedType() {
    return "com.mongodb.internal.connection.BaseCluster";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".CallbackWrapper"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("selectServerAsync"))
            .and(takesArgument(2, named("com.mongodb.internal.async.SingleResultCallback"))),
        packageName + ".Arg2Advice");
  }
}
