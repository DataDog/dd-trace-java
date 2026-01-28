package datadog.trace.instrumentation.java.completablefuture;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;

/**
 * Instruments classes used internally by {@code CompletableFuture} which are double instrumented as
 * {@code Runnable} and {@code ForkJoinTask} and can't be excluded because they can be used in
 * either context. This double instrumentation otherwise leads to excess scope creation and
 * duplicate checkpoint emission.
 */
public final class AsyncTaskInstrumentation
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  static final String[] CLASS_NAMES = {
    "java.util.concurrent.CompletableFuture$AsyncSupply",
    "java.util.concurrent.CompletableFuture$AsyncRun",
  };

  @Override
  public String[] knownMatchingTypes() {
    return CLASS_NAMES;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformer.applyAdvice(named("run"), getClass().getName() + "$Run");
    transformer.applyAdvice(named("cancel"), getClass().getName() + "$Cancel");
  }

  public static class Construct {
    @Advice.OnMethodExit
    public static void construct(@Advice.This ForkJoinTask<?> task) {
      capture(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
    }
  }

  public static class Run {
    @Advice.OnMethodEnter
    public static AgentScope before(@Advice.This ForkJoinTask<?> zis) {
      return startTaskScope(InstrumentationContext.get(ForkJoinTask.class, State.class), zis);
    }

    @Advice.OnMethodExit
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }

  public static class Cancel {
    @Advice.OnMethodExit
    public static <T> void cancel(@Advice.This ForkJoinTask<T> task) {
      State state = InstrumentationContext.get(ForkJoinTask.class, State.class).get(task);
      if (null != state) {
        state.closeContinuation();
      }
    }
  }
}
