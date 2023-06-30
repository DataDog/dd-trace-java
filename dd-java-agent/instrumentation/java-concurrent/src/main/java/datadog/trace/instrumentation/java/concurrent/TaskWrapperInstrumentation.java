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
            "task",
            "java.util.concurrent.CompletableFuture$AsyncSupply",
            "fn",
            "java.util.concurrent.CompletableFuture$AsyncRun",
            "fn",
            "java.util.concurrent.ForkJoinTask$AdaptedRunnable",
            "runnable",
            "java.util.concurrent.ForkJoinTask$AdaptedCallable",
            "callable",
            "java.util.concurrent.ForkJoinTask$AdaptedRunnableAction",
            "runnable"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {}

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "java.util.concurrent.FutureTask",
      "java.util.concurrent.Executors$RunnableAdapter",
      "java.util.concurrent.CompletableFuture$AsyncSupply",
      "java.util.concurrent.CompletableFuture$AsyncRun",
      "java.util.concurrent.ForkJoinTask$AdaptedRunnable",
      "java.util.concurrent.ForkJoinTask$AdaptedCallable",
      "java.util.concurrent.ForkJoinTask$AdaptedRunnableAction"
    };
  }
}
