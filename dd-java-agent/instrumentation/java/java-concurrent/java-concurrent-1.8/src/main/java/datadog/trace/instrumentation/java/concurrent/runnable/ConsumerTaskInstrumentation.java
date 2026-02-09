package datadog.trace.instrumentation.java.concurrent.runnable;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;

/**
 * Instrument the Runnable implementation of {@code ConsumerTask} (JDK 9+) where the ForkJoinTask
 * parent class is already instrumented by {@link
 * datadog.trace.instrumentation.java.concurrent.forkjoin.JavaForkJoinTaskInstrumentation}
 */
@AutoService(InstrumenterModule.class)
public class ConsumerTaskInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ConsumerTaskInstrumentation() {
    super(EXECUTOR_INSTRUMENTATION_NAME, "consumer-task");
  }

  @Override
  public String instrumentedType() {
    return "java.util.concurrent.SubmissionPublisher$ConsumerTask";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    // execution will be instrumented as ForkJoinTask
    transformer.applyAdvice(named("run"), getClass().getName() + "$Run");
  }

  public static class Construct {
    @Advice.OnMethodExit
    public static void construct(@Advice.This ForkJoinTask<?> task) {
      AgentSpan span = activeSpan();
      if (null != span) {
        State state = State.FACTORY.create();
        state.captureAndSetContinuation(span);
        InstrumentationContext.get(ForkJoinTask.class, State.class).put(task, state);
      }
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <T> AgentScope before(@Advice.This ForkJoinTask<T> task) {
      return startTaskScope(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }
}
