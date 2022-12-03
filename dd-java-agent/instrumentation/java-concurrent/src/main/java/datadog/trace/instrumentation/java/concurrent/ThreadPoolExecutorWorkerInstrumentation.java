package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.concurrent.ThreadPoolExecutor;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ThreadPoolExecutorWorkerInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public ThreadPoolExecutorWorkerInstrumentation() {
    super(AbstractExecutorInstrumentation.EXEC_NAME, "pool-parallelism", "tpe-parallelism");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.ThreadPoolExecutor$Worker";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("run")).and(takesArguments(0)),
        getClass().getName() + "$RecordWorkerParallelism");
  }

  public static final class RecordWorkerParallelism {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.FieldValue("this$0") ThreadPoolExecutor tpe) {
      int parallelism =
          tpe.getMaximumPoolSize() == Integer.MAX_VALUE
              ? tpe.getCorePoolSize()
              : tpe.getMaximumPoolSize();
      if (parallelism != 0) {
        AgentTracer.get().recordCurrentThreadPoolParallelism(parallelism);
      }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit() {
      AgentTracer.get().clearCurrentThreadPoolParallelism();
    }
  }
}
