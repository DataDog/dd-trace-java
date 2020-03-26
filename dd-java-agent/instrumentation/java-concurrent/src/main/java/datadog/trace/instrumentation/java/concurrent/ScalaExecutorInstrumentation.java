package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.nameMatches;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.concurrent.forkjoin.ForkJoinTask;

@Slf4j
@AutoService(Instrumenter.class)
public final class ScalaExecutorInstrumentation extends AbstractExecutorInstrumentation {

  public ScalaExecutorInstrumentation() {
    super(EXEC_NAME + ".scala_fork_join");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        ScalaForkJoinTaskInstrumentation.TASK_CLASS_NAME, State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("execute")
            .and(takesArgument(0, named(ScalaForkJoinTaskInstrumentation.TASK_CLASS_NAME))),
        ScalaExecutorInstrumentation.class.getName() + "$SetScalaForkJoinStateAdvice");
    transformers.put(
        named("submit")
            .and(takesArgument(0, named(ScalaForkJoinTaskInstrumentation.TASK_CLASS_NAME))),
        ScalaExecutorInstrumentation.class.getName() + "$SetScalaForkJoinStateAdvice");
    transformers.put(
        nameMatches("invoke")
            .and(takesArgument(0, named(ScalaForkJoinTaskInstrumentation.TASK_CLASS_NAME))),
        ScalaExecutorInstrumentation.class.getName() + "$SetScalaForkJoinStateAdvice");
    return transformers;
  }

  public static class SetScalaForkJoinStateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State enterJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Argument(value = 0, readOnly = false) final ForkJoinTask task) {
      final TraceScope scope = activeScope();
      if (ExecutorInstrumentationUtils.shouldAttachStateToTask(task, executor)) {
        final ContextStore<ForkJoinTask, State> contextStore =
            InstrumentationContext.get(ForkJoinTask.class, State.class);
        return ExecutorInstrumentationUtils.setupState(contextStore, task, scope);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitJobSubmit(
        @Advice.This final Executor executor,
        @Advice.Enter final State state,
        @Advice.Thrown final Throwable throwable) {
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(executor, state, throwable);
    }
  }
}
