package datadog.trace.instrumentation.java.concurrent.structuredconcurrency25;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.trace.bootstrap.InstrumentationContext.get;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.bootstrap.instrumentation.java.concurrent.StructuredTaskScopeHelper;
import java.util.Map;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.Return;
import net.bytebuddy.asm.Advice.This;

/**
 * This instrumentation cancels the continuation captured by {@link
 * StructuredTaskScope25Instrumentation} for a subtask forked into an already-canceled scope.
 *
 * <p>In that case, the subtask's thread is never started, so {@code SubtaskImpl.run()} never runs,
 * and the {@link Runnable} instrumentation never activates/closes the captured continuation. It
 * leads to continuation leak. This instrumentation verifies the subtask was canceled and releases
 * the related continuation.
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public class StructuredTaskScopeForkInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public StructuredTaskScopeForkInstrumentation() {
    super("java_concurrent", "structured-task-scope", "structured-task-scope-25");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.StructuredTaskScopeImpl";
  }

  @Override
  public boolean isEnabled() {
    return isJavaVersionAtLeast(25) && super.isEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("fork").and(takesArgument(0, Callable.class)), getClass().getName() + "$ForkAdvice");
  }

  public static final class ForkAdvice {
    @OnMethodExit(suppress = Throwable.class)
    public static void afterFork(@This Object scope, @Return Object subtask) {
      if (subtask instanceof Runnable && StructuredTaskScopeHelper.isCancelled(scope)) {
        ContextStore<Runnable, State> contextStore = get(Runnable.class, State.class);
        cancelTask(contextStore, (Runnable) subtask);
      }
    }
  }
}
