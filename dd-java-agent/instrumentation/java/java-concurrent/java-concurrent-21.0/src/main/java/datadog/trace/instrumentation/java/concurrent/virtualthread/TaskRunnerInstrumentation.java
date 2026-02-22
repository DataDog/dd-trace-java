package datadog.trace.instrumentation.java.concurrent.virtualthread;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;

/**
 * Instruments {@code TaskRunner}, internal runnable for {@code ThreadPerTaskExecutor} (JDK 19+ as
 * preview, 21+ as stable), the executor with default virtual thread factory.
 */
@SuppressWarnings("unused")
@AutoService(InstrumenterModule.class)
public final class TaskRunnerInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public TaskRunnerInstrumentation() {
    super("java_concurrent", "task-runner");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.ThreadPerTaskExecutor$TaskRunner";
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(19) && super.isEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> stores = new HashMap<>(2);
    stores.put(Runnable.class.getName(), State.class.getName());
    // TaskRunner may wrap a RunnableFuture that already has captured context.
    // Expose this store so Construct can reuse that existing state and avoid creating
    // a second continuation for the same logical task.
    stores.put(RunnableFuture.class.getName(), State.class.getName());
    return Collections.unmodifiableMap(stores);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformer.applyAdvice(isMethod().and(named("run")), getClass().getName() + "$Run");
  }

  public static final class Construct {
    @OnMethodExit(suppress = Throwable.class)
    public static void captureScope(
        @Advice.This Runnable task, @Advice.FieldValue("task") Runnable innerTask) {
      if (innerTask instanceof RunnableFuture) {
        // `submit(...)` creates a FutureTask and then wraps it in TaskRunner.
        // The FutureTask constructor is already instrumented and captures continuation once.
        // Reuse the same State so we don't capture a second continuation here.
        State innerState =
            InstrumentationContext.get(RunnableFuture.class, State.class)
                .get((RunnableFuture<?>) innerTask);
        if (innerState != null) {
          InstrumentationContext.get(Runnable.class, State.class).put(task, innerState);
          return;
        }
      }
      // Plain execute(Runnable) path where there is no wrapped RunnableFuture state to reuse.
      capture(InstrumentationContext.get(Runnable.class, State.class), task);
    }
  }

  public static final class Run {
    @OnMethodEnter(suppress = Throwable.class)
    public static AgentScope activate(@Advice.This Runnable task) {
      return startTaskScope(InstrumentationContext.get(Runnable.class, State.class), task);
    }

    @OnMethodExit(suppress = Throwable.class)
    public static void close(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }
}
