package datadog.trace.instrumentation.java.concurrent.executor;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class NonStandardExecutorInstrumentation extends AbstractExecutorInstrumentation {
  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // kotlinx.coroutines.scheduling.CoroutineScheduler
    transformer
      // kotlinx.coroutines.scheduling.CoroutineScheduler
      .applyAdvice(
          named("dispatch")
            .and(takesArgument(0, Runnable.class))
            .and(takesArgument(1, named("kotlinx.coroutines.scheduling.TaskContext"))),
          JavaExecutorInstrumentation.class.getName() + "$SetExecuteRunnableStateAdvice"
      );
    // org.eclipse.jetty.util.thread.QueuedThreadPool
    transformer
      // org.eclipse.jetty.util.thread.QueuedThreadPool
      .applyAdvice(
          named("dispatch").and(takesArguments(1)).and(takesArgument(0, Runnable.class)),
          JavaExecutorInstrumentation.class.getName() + "$SetExecuteRunnableStateAdvice"
      );
  }
}
