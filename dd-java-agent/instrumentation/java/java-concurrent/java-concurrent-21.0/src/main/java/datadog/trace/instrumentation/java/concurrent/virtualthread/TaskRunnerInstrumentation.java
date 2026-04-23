package datadog.trace.instrumentation.java.concurrent.virtualthread;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
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
    return singletonMap("java.lang.Runnable", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformer.applyAdvice(isMethod().and(named("run")), getClass().getName() + "$Run");
  }

  public static final class Construct {
    @OnMethodExit(suppress = Throwable.class)
    public static void captureScope(@Advice.This Runnable task) {
      capture(InstrumentationContext.get(Runnable.class, State.class), task);
    }
  }

  public static final class Run {
    /**
     * Stores {@code System.nanoTime()} at task activation so {@link #close} can compute the
     * synthetic-SpanNode duration. Uses a dedicated ThreadLocal instead of {@code
     * TPEHelper.threadLocalActivationNano} so this instrumentation works regardless of whether
     * legacy thread-pool-executor wrapping mode is enabled (in legacy mode {@code
     * TPEHelper.threadLocalActivationNano} is null and the helpers silently no-op).
     */
    private static final ThreadLocal<Long> TASK_START_NANO = new ThreadLocal<>();

    @OnMethodEnter(suppress = Throwable.class)
    public static AgentScope activate(@Advice.This Runnable task) {
      AgentScope scope =
          startTaskScope(InstrumentationContext.get(Runnable.class, State.class), task);
      if (scope != null) {
        long startNano = System.nanoTime();
        // noinspection ThreadLocalSetWithNull — use set(null) instead of remove() for performance
        TASK_START_NANO.set(startNano);
        AgentSpan span = scope.span();
        if (span != null && span.context() instanceof ProfilerContext) {
          // onTaskActivation skips virtual threads (isVirtual=true) so captureExecutionThread
          // is not called — intentional, the carrier thread can't be identified via
          // Thread.currentThread() from a virtual thread context.
          AgentTracer.get()
              .getProfilingContext()
              .onTaskActivation((ProfilerContext) span.context(), startNano);
        }
      }
      return scope;
    }

    @OnMethodExit(suppress = Throwable.class)
    public static void close(@Advice.Enter AgentScope scope) {
      Long startNano = TASK_START_NANO.get();
      // noinspection ThreadLocalSetWithNull
      TASK_START_NANO.set(null);
      // onTaskDeactivation emits a synthetic SpanNode JFR event attributed to the ForkJoin
      // carrier thread (ProfiledThread::currentTid() = carrier OS tid → CPOOL → Java thread ID).
      // Must be called BEFORE endTaskScope so the span context is still active.
      if (scope != null && startNano != null) {
        AgentSpan span = scope.span();
        if (span != null && span.context() instanceof ProfilerContext) {
          AgentTracer.get()
              .getProfilingContext()
              .onTaskDeactivation((ProfilerContext) span.context(), startNano);
        }
      }
      endTaskScope(scope);
    }
  }
}
