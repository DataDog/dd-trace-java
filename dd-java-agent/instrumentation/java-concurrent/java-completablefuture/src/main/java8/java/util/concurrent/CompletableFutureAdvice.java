package java.util.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.concurrent.CompletableFuture.UniCompletion;
import net.bytebuddy.asm.Advice;

// This class is put into java.util.concurrent to allow access to package private classes.
public final class CompletableFutureAdvice {

  public static final class UniConstructor {
    @Advice.OnMethodExit() // suppress = Throwable.class)
    public static void afterInit(@Advice.This UniCompletion thiz) {
      TraceScope scope = activeScope();
      if (thiz.isLive() && scope != null) {
        ContextStore<UniCompletion, State> contextStore =
            InstrumentationContext.get(UniCompletion.class, State.class);
        ExecutorInstrumentationUtils.setupState(contextStore, thiz, scope);
      }
    }

    private static void muzzleCheck(final UniCompletion callback) {
      callback.exec();
    }
  }

  public static final class UniSubTryFire {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceScope enter(
        @Advice.This UniCompletion thiz,
        @Advice.Argument(0) int mode, // TODO only debugging
        @Advice.Local("executor") Executor executor,
        @Advice.Local("dep") CompletableFuture dep) { // TODO only debuggin
      dep = thiz.dep;
      // To be able to decide if we should cancel a continuation in the `exit` advice,
      // we need to keep track of if we had an executor when entering `tryFire`, since
      // the field is cleared after a successful tryFire before we reach the `exit` advice
      executor = thiz.executor;
      // If this UniCompletion is not live, then we don't need to activate a span
      if (!thiz.isLive()) {
        return null;
      }
      ContextStore<UniCompletion, State> contextStore =
          InstrumentationContext.get(UniCompletion.class, State.class);
      // Since there can be multiple threads trying to check if the CompletableFuture that
      // we depend on is completed and then run this UniCompletion, we need to ensure
      // that there is always a continuation even if we activate a scope
      TraceScope scope = AdviceUtils.startAndContinueTaskScope(contextStore, thiz);
      // TODO This should not pick up the active scope here if it doesn't have one (per the
      // discussion)
      if (scope == null && dep.result == null) {
        TraceScope parentScope = activeScope();
        ExecutorInstrumentationUtils.setupState(contextStore, thiz, parentScope);
      }

      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.Enter TraceScope scope,
        @Advice.This UniCompletion thiz,
        @Advice.Argument(0) int mode,
        @Advice.Local("executor") Executor executor,
        @Advice.Local("dep") CompletableFuture dep) {
      // Try to clean up if the CompletableFuture has been completed
      // Either we are done and `isLive` is false, or we had no executor when we entered `tryFire`
      // and have now claimed the task which can happen in `thenCompose` where it hands off to a
      // `UniRelay`
      if (!thiz.isLive() || (executor == null && thiz.getForkJoinTaskTag() == 1)) {
        ContextStore<UniCompletion, State> contextStore =
            InstrumentationContext.get(UniCompletion.class, State.class);
        AdviceUtils.cancelContinuationIfPossible(contextStore, thiz);
      }
      AdviceUtils.endTaskScope(scope);
    }

    private static void muzzleCheck(final UniCompletion callback) {
      callback.exec();
    }
  }
}
