package datadog.trace.instrumentation.java.concurrent;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.profiling.UnwrappingVisitor;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

@AutoService(Instrumenter.class)
public class TaskUnwrappingInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForKnownTypes {
  public TaskUnwrappingInstrumentation() {
    super("java_concurrent", "task-unwrapping");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(
                ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED,
                ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT);
  }

  private static final String[] TYPES_WITH_FIELDS = {
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
    "runnable",
    "java.util.concurrent.ForkJoinTask$RunnableExecuteAction",
    "runnable",
    // netty
    "io.netty.util.concurrent.PromiseTask$RunnableAdapter",
    "task",
    "io.netty.util.concurrent.PromiseTask",
    "task",
    // netty shaded into gRPC
    "io.grpc.netty.shaded.io.netty.util.concurrent.PromiseTask$RunnableAdapter",
    "task",
    "io.grpc.netty.shaded.io.netty.util.concurrent.PromiseTask",
    "task"
  };

  @Override
  public AdviceTransformer transformer() {
    return new VisitingTransformer(new UnwrappingVisitor(TYPES_WITH_FIELDS));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {}

  @Override
  public String[] knownMatchingTypes() {
    String[] types = new String[TYPES_WITH_FIELDS.length / 2];
    for (int i = 0; i < types.length; i++) {
      types[i] = TYPES_WITH_FIELDS[i * 2];
    }
    return types;
  }
}
