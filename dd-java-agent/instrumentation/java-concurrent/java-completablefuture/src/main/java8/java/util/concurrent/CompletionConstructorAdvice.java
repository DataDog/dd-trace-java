package java.util.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import net.bytebuddy.asm.Advice;

// This class is put into java.util.concurrent to allow access to package private Completion class.
public class CompletionConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onConstruct(@Advice.This CompletableFuture.Completion task) {
    final TraceScope scope = activeScope();
    if (scope != null) {
      final ContextStore<Runnable, State> contextStore =
          InstrumentationContext.get(Runnable.class, State.class);
      ExecutorInstrumentationUtils.setupState(contextStore, task, scope);
    }
  }

  private static void muzzleCheck(final CompletableFuture.Completion callback) {
    callback.exec();
  }
}
