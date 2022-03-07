package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;

@AutoService(Instrumenter.class)
public final class NonStandardExecutorInstrumentation extends AbstractExecutorInstrumentation {

  public NonStandardExecutorInstrumentation() {
    super(EXEC_NAME + ".other");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice( // kotlinx.coroutines.scheduling.CoroutineScheduler
        named("dispatch")
            .and(takesArgument(0, Runnable.class))
            .and(takesArgument(1, named("kotlinx.coroutines.scheduling.TaskContext"))),
        JavaExecutorInstrumentation.class.getName() + "$SetExecuteRunnableStateAdvice");

    transformation.applyAdvice( // org.eclipse.jetty.util.thread.QueuedThreadPool
        named("dispatch").and(takesArguments(1)).and(takesArgument(0, Runnable.class)),
        JavaExecutorInstrumentation.class.getName() + "$SetExecuteRunnableStateAdvice");
  }
}
