package datadog.trace.instrumentation.java.concurrent;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;

/** Captures context for one-shot tasks scheduled through ForkJoinPool's delay scheduler. */
@AutoService(InstrumenterModule.class)
public final class ScheduledForkJoinTaskInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ScheduledForkJoinTaskInstrumentation() {
    super("java_concurrent", "fjp");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.DelayScheduler$ScheduledForkJoinTask";
  }

  @Override
  public boolean isEnabled() {
    return isJavaVersionAtLeast(25) && super.isEnabled();
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
  }

  public static final class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureContext(
        @Advice.This ForkJoinTask<?> task,
        @Advice.FieldValue("nextDelay") long nextDelay,
        @Advice.FieldValue("isImmediate") boolean isImmediate) {
      // Periodic tasks must not retain their creator; immediate tasks are internal timeout actions.
      if (nextDelay == 0 && !isImmediate) {
        capture(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
      }
    }
  }
}
