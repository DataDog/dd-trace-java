package datadog.trace.instrumentation.java.concurrent.executor;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;

public final class JavaExecutorInstrumentation extends AbstractExecutorInstrumentation {
  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("execute").and(takesArgument(0, Runnable.class)).and(takesArguments(1)),
        JavaExecutorInstrumentation.class.getName() + "$SetExecuteRunnableStateAdvice");
  }

  public static class SetExecuteRunnableStateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State enterJobSubmit(
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (task instanceof RunnableFuture) {
        return null;
      }
      // there are cased like ScheduledExecutorService.submit (which we instrument)
      // which calls ScheduledExecutorService.schedule (which we also instrument)
      // where all of this could be dodged the second time
      final AgentSpan span = activeSpan();
      if (null != span) {
        final Runnable newTask = RunnableWrapper.wrapIfNeeded(task);
        // It is important to check potentially wrapped task if we can instrument task in this
        // executor. Some executors do not support wrapped tasks.
        if (ExecutorInstrumentationUtils.shouldAttachStateToTask(newTask, span)) {
          task = newTask;
          final ContextStore<Runnable, State> contextStore =
              InstrumentationContext.get(Runnable.class, State.class);
          return ExecutorInstrumentationUtils.setupState(contextStore, newTask, span);
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitJobSubmit(
        @Advice.Enter final State state, @Advice.Thrown final Throwable throwable) {
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(state, throwable);
    }
  }
}
