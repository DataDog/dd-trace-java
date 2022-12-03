package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.ForkJoinWorkerThread;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class JavaForkJoinWorkerThreadInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {
  public JavaForkJoinWorkerThreadInstrumentation() {
    super(AbstractExecutorInstrumentation.EXEC_NAME, "pool-parallelism", "fjp-parallelism");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.ForkJoinWorkerThread";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("run")).and(takesArguments(0)),
        getClass().getName() + "$RecordPoolParallelism");
  }

  public static final class RecordPoolParallelism {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This ForkJoinWorkerThread thread) {
      AgentTracer.get().recordCurrentThreadPoolParallelism(thread.getPool().getParallelism());
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit() {
      AgentTracer.get().clearCurrentThreadPoolParallelism();
    }
  }
}
