package datadog.trace.instrumentation.java.concurrent;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.profiling.UnwrappingVisitor;

@AutoService(Instrumenter.class)
public class TaskWrapperInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForKnownTypes {
  public TaskWrapperInstrumentation() {
    super("java_concurrent", "wrapper-task");
  }

  @Override
  public AdviceTransformer transformer() {
    return new VisitingTransformer(
        new UnwrappingVisitor(
            "java.util.concurrent.FutureTask",
            "callable",
            "java.util.concurrent.Executors$RunnableAdapter",
            "task"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {}

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "java.util.concurrent.FutureTask", "java.util.concurrent.Executors$RunnableAdapter"
    };
  }
}
