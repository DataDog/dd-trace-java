package datadog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.InstrumentationContext.get;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.StructuredTaskScopeHelper;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.Return;
import net.bytebuddy.asm.Advice.This;

/**
 * This instrumentation cancels the continuation captured by {@link
 * StructuredTaskScope25TaskInstrumentation} for a subtask forked into an already-canceled scope.
 *
 * <p>In that case, the subtask's thread is never started, so {@code SubtaskImpl.run()} never runs,
 * and the {@link Runnable} instrumentation never activates/closes the captured continuation. It
 * leads to continuation leak. This instrumentation verifies the subtask was canceled and releases
 * the related continuation.
 */
@SuppressWarnings("unused")
public class StructuredTaskScope25Instrumentation
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.StructuredTaskScopeImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("fork").and(takesArgument(0, Callable.class)), getClass().getName() + "$ForkAdvice");
  }

  public static final class ForkAdvice {
    /**
     * Cancels the task scope continuation captured at task creation when the subtask is forked into
     * an already-canceled scope: its thread never starts, so the {@link Runnable} instrumentation
     * never releases the continuation.
     *
     * @param scope The StructuredTaskScopeImpl object (the advice is compiled against Java 8 so the
     *     type from JDK25 can't be referred, using {@link Object} instead).
     * @param subtask The StructuredTaskScopeImpl.SubtaskImpl object (the advice is compiled against
     *     Java 8 so the type from JDK25 can't be referred, using {@link Object} instead).
     */
    @OnMethodExit(suppress = Throwable.class)
    public static void afterFork(@This Object scope, @Return Object subtask) {
      if (subtask instanceof Runnable && StructuredTaskScopeHelper.isCancelled(scope)) {
        ContextStore<Runnable, State> contextStore = get(Runnable.class, State.class);
        cancelTask(contextStore, (Runnable) subtask);
      }
    }
  }
}
