package datadog.trace.instrumentation.guava10;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.google.common.util.concurrent.AbstractFuture;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.concurrent.Executor;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ListenableFutureInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ListenableFutureInstrumentation() {
    super("guava");
  }

  @Override
  public String instrumentedType() {
    return "com.google.common.util.concurrent.AbstractFuture";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      this.packageName + ".GuavaAsyncResultExtension",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("addListener").and(takesArguments(Runnable.class, Executor.class)),
        ListenableFutureInstrumentation.class.getName() + "$AddListenerAdvice");
  }

  public static class AddListenerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State addListenerEnter(
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
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
    public static void addListenerExit(
        @Advice.Enter final State state, @Advice.Thrown final Throwable throwable) {
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(state, throwable);
    }

    private static void muzzleCheck(final AbstractFuture<?> future) {
      future.addListener(null, null);
    }
  }
}
