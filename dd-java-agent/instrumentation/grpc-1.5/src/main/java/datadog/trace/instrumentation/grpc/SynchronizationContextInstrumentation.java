package datadog.trace.instrumentation.grpc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class SynchronizationContextInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForSingleType {

  public SynchronizationContextInstrumentation() {
    super("grpc", "grpc-queueing-time");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".TimedRunnable"};
  }

  @Override
  public String instrumentedType() {
    return "io.grpc.SynchronizationContext";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(namedOneOf("executeLater", "schedule").and(takesArgument(0, Runnable.class))),
        packageName + ".RecordQueueingTimeAdvice");
  }
}
