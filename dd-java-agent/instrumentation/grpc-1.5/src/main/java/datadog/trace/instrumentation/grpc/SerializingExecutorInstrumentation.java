package datadog.trace.instrumentation.grpc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class SerializingExecutorInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForSingleType {
  public SerializingExecutorInstrumentation() {
    super("grpc", "grpc-queueing-time");
  }

  @Override
  public String instrumentedType() {
    return "io.grpc.internal.SerializingExecutor";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("execute").and(takesArguments(Runnable.class))),
        packageName + ".RecordQueueingTimeAdvice");
  }
}
